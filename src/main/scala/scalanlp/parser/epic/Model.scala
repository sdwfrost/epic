package scalanlp.parser.epic

import scalanlp.optimize.BatchDiffFunction
import collection.GenTraversable
import scalala.tensor.sparse.SparseVector
import scalala.tensor.dense.{DenseVectorCol, DenseVector}
import actors.threadpool.AtomicInteger
import scalanlp.parser.features.Feature
import scalanlp.util.{Encoder, Index}
import java.io.File


trait Model[Datum] { self =>
  type ExpectedCounts <: scalanlp.parser.epic.ExpectedCounts[ExpectedCounts]
  type Inference <: scalanlp.parser.epic.Inference[Datum] {
    type ExpectedCounts = self.ExpectedCounts
  }

  def featureIndex: Index[Feature]
  def numFeatures = featureIndex.size

  // just saves feature weights to disk as a serialized counter. The file is prefix.ser.gz
  def cacheFeatureWeights(weights: DenseVector[Double], prefix: String = "weights") {
    val ctr = Encoder.fromIndex(featureIndex).decode(weights)
    scalanlp.util.writeObject(new File(prefix+".ser.gz"),  ctr)
  }

  def initialValueForFeature(f: Feature):Double// = 0

  def inferenceFromWeights(weights: DenseVector[Double]):Inference

  def emptyCounts: ExpectedCounts
  def expectedCountsToObjective(ecounts: ExpectedCounts):(Double,DenseVectorCol[Double])
}
/**
 * 
 * @author dlwh
 */
class ModelObjective[Datum](val model: Model[Datum],
                            batchSelector: IndexedSeq[Int]=>GenTraversable[Datum],
                            val fullRange: IndexedSeq[Int]) extends BatchDiffFunction[DenseVector[Double]] {
  def this(model: Model[Datum], data: IndexedSeq[Datum]) = this(model,_.par.map(data), 0 until data.length)
  import model.{ExpectedCounts => _, _}

  type Builder = model.Inference

  // Selects a set of data to use
  protected def select(batch: IndexedSeq[Int]):GenTraversable[Datum] = batchSelector(batch)

  def initialWeightVector(randomize: Boolean): DenseVector[Double] = {
   val v = Encoder.fromIndex(featureIndex).tabulateDenseVector(f => model.initialValueForFeature(f))
    if(randomize) {
      v += DenseVector.rand(numFeatures) * 1E-5
    }
    v
  }

  var iter = 0

  def calculate(x: DenseVector[Double], batch: IndexedSeq[Int]) = {
    if(iter % 10 == 0) {
      model.cacheFeatureWeights(x, "weights")
    }
    iter += 1
    val inference = inferenceFromWeights(x)
    val timeIn = System.currentTimeMillis()
    val success = new AtomicInteger(0)
    val finalCounts = select(batch).aggregate(emptyCounts)({ (countsSoFar,datum) =>
      try {
        val counts = inference.expectedCounts(datum) += countsSoFar
        success.incrementAndGet()
        counts
      } catch {
        case e =>
          new Exception("While processing " + datum, e).printStackTrace()
          countsSoFar
      }
    },{ (a,b) => b += a})
    val timeOut = System.currentTimeMillis()
    println("Parsing took: " + (timeOut - timeIn) * 1.0/1000 + "s" )

    val (loss,grad) = expectedCountsToObjective(finalCounts)
    val timeOut2 = System.currentTimeMillis()
    println("Finishing took: " + (timeOut2 - timeOut) * 1.0/1000 + "s" )
    (loss/success.intValue() * fullRange.size,  grad * (fullRange.size * 1.0 / success.intValue))
  }
}

trait ExpectedCounts[Self<:ExpectedCounts[Self]] { this:Self =>
  def +=(other: Self):Self
  def -=(other: Self):Self
  def loss: Double
}