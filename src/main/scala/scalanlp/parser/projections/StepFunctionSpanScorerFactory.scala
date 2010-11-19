package scalanlp.parser
package projections

/**
 * Takes another SpanScorer.Factory, and thresholds its outputs so that any thing > threshold is 0.0, and
 * anything else is Double.NegativeInfinity
 *
 * @author dlwh
 */
class StepFunctionSpanScorerFactory[L,W](innerFactory: SpanScorer.Factory[W], threshold: Double=1E-10) extends SpanScorer.Factory[W] {
  def mkSpanScorer(s: scala.Seq[W], oldScorer: SpanScorer= ChartBuilder.defaultScorer):SpanScorer = {
    val inner = innerFactory.mkSpanScorer(s,oldScorer);
    new SpanScorer {
      @inline def I(score: Double) = if(score > threshold) 0.0 else Double.NegativeInfinity;

      def scoreLexical(begin: Int, end: Int, tag: Int) = I(inner.scoreLexical(begin,end,tag))

      def scoreUnaryRule(begin: Int, end: Int, parent: Int, child: Int) = I(scoreUnaryRule(begin,end,parent,child));

      def scoreBinaryRule(begin: Int, split: Int, end: Int, parent: Int, leftChild: Int, rightChild: Int) = {
        I(scoreBinaryRule(begin, split, end, parent, leftChild, rightChild))
      }
    }

  }
}
