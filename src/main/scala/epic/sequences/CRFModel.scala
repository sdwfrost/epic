package epic.sequences

import epic.framework._
import breeze.util._
import breeze.linalg._
import scala.collection.mutable.ArrayBuffer
import epic.sequences.CRF.{AnchoredFeaturizer, TransitionVisitor}
import epic.parser.features.{LabelFeature, PairFeature}
import breeze.text.analyze.{WordShapeGenerator, EnglishWordClassGenerator}
import scala.collection
import java.io.{BufferedWriter, FileWriter, PrintWriter}
import scala.collection.immutable
import breeze.features.FeatureVector
import epic.features.{ContextualSurfaceFeaturizer, WordShapeFeaturizer}
import epic.lexicon.{Lexicon, SimpleLexicon}

/**
 *
 * @author dlwh
 */
@SerialVersionUID(1L)
class CRFModel[L, W](val featureIndex: Index[Feature],
                         val featurizer: CRF.IndexedFeaturizer[L, W],
                         initialWeights: Feature=>Double = {(_: Feature) => 0.0}) extends Model[TaggedSequence[L, W]] with StandardExpectedCounts.Model with Serializable {
  def labelIndex: Index[L] = featurizer.labelIndex

  def extractCRF(weights: DenseVector[Double]) = {
    val grammar = inferenceFromWeights(weights)
    new CRF(grammar)
  }

  type Inference = CRFInference[L, W]
  type Marginal = CRF.Marginal[L, W]

  def initialValueForFeature(f: Feature): Double = initialWeights(f)

  def inferenceFromWeights(weights: DenseVector[Double]): Inference =
    new CRFInference(weights, featureIndex, featurizer)

}


@SerialVersionUID(1)
class CRFInference[L, W](weights: DenseVector[Double],
                         featureIndex: Index[Feature],
                         featurizer: CRF.IndexedFeaturizer[L, W]) extends AugmentableInference[TaggedSequence[L, W], CRF.Anchoring[L, W]] with CRF.Grammar[L, W] with Serializable {
  def viterbi(sentence: IndexedSeq[W], anchoring: CRF.Anchoring[L, W]): TaggedSequence[L, W] = {
    CRF.viterbi(new Anchoring(sentence, anchoring))
  }


  type Marginal = CRF.Marginal[L, W]
  type ExpectedCounts = StandardExpectedCounts[Feature]

  def emptyCounts = StandardExpectedCounts.zero(this.featureIndex)

  def anchor(w: IndexedSeq[W]) = new Anchoring(w, new IdentityAnchoring(w))


  def labelIndex = featurizer.labelIndex
  def startSymbol = featurizer.startSymbol

  def marginal(v: TaggedSequence[L, W], aug: CRF.Anchoring[L, W]): Marginal = {
    CRF.Marginal(new Anchoring(v.words, aug))
  }

  def goldMarginal(v: TaggedSequence[L, W], augment: CRF.Anchoring[L, W]): CRF.Marginal[L, W] = {
    CRF.Marginal.goldMarginal[L, W](new Anchoring(v.words, augment), v.label)
  }

  def countsFromMarginal(v: TaggedSequence[L, W], marg: Marginal, counts: ExpectedCounts, scale: Double): ExpectedCounts = {
    counts.loss += marg.logPartition * scale
    val localization = marg.anchoring.asInstanceOf[Anchoring].localization
    val visitor = new TransitionVisitor[L, W] {

      def apply(pos: Int, prev: Int, cur: Int, count: Double) {
        val feats = localization.featuresForTransition(pos, prev, cur)
        if(count != 0) assert(feats ne null, (pos, prev, cur, marg.length, marg.anchoring.validSymbols(pos), marg.anchoring.validSymbols(pos-1)))
        axpy(scale * count, feats, counts.counts)
      }
    }
    marg.visit(visitor)
    counts
  }


  def baseAugment(v: TaggedSequence[L, W]): CRF.Anchoring[L, W] = new IdentityAnchoring(v.words)

  class IdentityAnchoring(val words: IndexedSeq[W]) extends CRF.Anchoring[L, W] {

    def labelIndex: Index[L] = featurizer.labelIndex

    def startSymbol: L = featurizer.startSymbol

    def validSymbols(pos: Int): Set[Int] = (0 until labelIndex.size).toSet

    def scoreTransition(pos: Int, prev: Int, cur: Int): Double = 0.0
  }

  class Anchoring(val words: IndexedSeq[W], augment: CRF.Anchoring[L, W]) extends CRF.Anchoring[L, W] {
    val localization = featurizer.anchor(words)

    val transCache = Array.tabulate(labelIndex.size, labelIndex.size, length){ (p,c,w) =>
      val f = localization.featuresForTransition(w, p, c)
      if (f eq null) Double.NegativeInfinity
      else weights dot f
    }


    def validSymbols(pos: Int): Set[Int] = localization.validSymbols(pos)

    def scoreTransition(pos: Int, prev: Int, cur: Int): Double = {
      augment.scoreTransition(pos, prev, cur) + transCache(prev)(cur)(pos)
    }

    def labelIndex: Index[L] = featurizer.labelIndex

    def startSymbol = featurizer.startSymbol
  }


  def posteriorDecode(m: Marginal):TaggedSequence[L, W] = {
    CRF.posteriorDecode(m)
  }
}

class TaggedSequenceModelFactory[L](val startSymbol: L,
                                    gazetteer: Gazetteer[Any, String] = Gazetteer.empty[String, String],
                                    weights: Feature=>Double = { (f:Feature) => 0.0}) {

  import TaggedSequenceModelFactory._

  def makeModel(train: IndexedSeq[TaggedSequence[L, String]]): CRFModel[L, String] = {
    val labelIndex: Index[L] = Index[L](Iterator(startSymbol) ++ train.iterator.flatMap(_.label))
    val counts: Counter2[L, String, Int] = Counter2.count(train.flatMap(p => p.label zip p.words))


    val f = ContextualSurfaceFeaturizer.forTrainingSet(train.map(_.words), gazetteer, needsContextFeatures = {(w, wc) =>  wc < 10 || counts(::, w).findAll(_ > 0).size != 1})
    val lexicon = new SimpleLexicon[L, String](labelIndex, counts.mapValues(_.toDouble))
    val featureIndex = Index[Feature]()

    val labelFeatures = (0 until labelIndex.size).map(l => LabelFeature(labelIndex.get(l)))
    val label2Features = for(l1 <- 0 until labelIndex.size) yield for(l2 <- 0 until labelIndex.size) yield LabelFeature(labelIndex.get(l1) -> labelIndex.get(l2))

    val labelWordFeatures = Array.fill(labelIndex.size, f.featureIndex.size)(-1)
    val label2WordFeatures = Array.fill(labelIndex.size, labelIndex.size, f.featureIndex.size)(-1)

    var i = 0
    for(s <- train) {
      val loc = f.anchor(s.words)
      val lexLoc = lexicon.anchor(s.words)

      for {
        b <- 0 until s.length
        l <- lexLoc.tagsForWord(b)
      } {
        loc.featuresForWord(b) foreach {f =>
          labelWordFeatures(l)(f) = featureIndex.index(PairFeature(labelFeatures(l), featureIndex.get(f)) )
        }
        if(lexLoc.tagsForWord(b).size > 1) {
          for(prevTag <- if(b == 0) Set(labelIndex(startSymbol)) else lexLoc.tagsForWord(b-1)) {
            loc.basicFeatures(b) foreach {f =>
              label2WordFeatures(l)(prevTag)(f) = featureIndex.index(PairFeature(label2Features(prevTag)(l), featureIndex.get(f)) )
            }
          }
        }
      }
      if(i % 500 == 0) {
        println(s"$i/${train.length} ${featureIndex.size}")
      }
      i += 1
    }

    val indexed = new IndexedStandardFeaturizer[L](f, lexicon, startSymbol, labelIndex, featureIndex, labelWordFeatures, label2WordFeatures)
    val model = new CRFModel(indexed.featureIndex, indexed, weights(_))

    model
  }

}

object TaggedSequenceModelFactory {


  @SerialVersionUID(1L)
  class IndexedStandardFeaturizer[L](wordFeaturizer: ContextualSurfaceFeaturizer,
                                     val lexicon: Lexicon[L, String],
                                     val startSymbol: L,
                                     val labelIndex: Index[L],
                                     val featureIndex: Index[Feature],
                                     labelFeatures: Array[Array[Int]],
                                     label2Features: Array[Array[Array[Int]]]) extends CRF.IndexedFeaturizer[L,String] with Serializable { outer =>



    def anchor(w: IndexedSeq[String]): AnchoredFeaturizer[L, String] = new AnchoredFeaturizer[L, String] {
      val loc = wordFeaturizer.anchor(w)
      val lexLoc = lexicon.anchor(w)
      def featureIndex: Index[Feature] =  outer.featureIndex

      def allowedTags(pos: Int): Set[Int] = if(pos < 0 || pos > w.length) Set(labelIndex(startSymbol)) else  lexLoc.tagsForWord(pos)

      val featureArray = Array.tabulate(w.length, labelIndex.size, labelIndex.size) { (pos, prevTag, l) =>
        if(allowedTags(pos)(l) && allowedTags(pos-1)(prevTag)) {
          val vb = collection.mutable.ArrayBuilder.make[Int]
          loc.featuresForWord(pos) foreach {f =>
            val fi1 = labelFeatures(l)(f)
            if(fi1 >= 0) {
              vb += fi1

              if(lexLoc.tagsForWord(pos).size > 1) {
                val fi2 = label2Features(prevTag)(l)(f)
                if(fi2 >= 0)
                  vb += fi2
              }
            }
          }
          new FeatureVector(vb.result())
        } else {
          null
        }
      }

      def featuresForTransition(pos: Int, prev: Int, cur: Int): FeatureVector = {
        featureArray(pos)(prev)(cur)
      }

      def validSymbols(pos: Int): Set[Int] = allowedTags(pos+1)
    }
  }


}