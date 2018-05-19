package pir
package node

trait PIRNodeUtil extends PIRContainer with PIRMemory with PIRDramFringe with PIRStreamFringe
  with PIRGlobalIO with PIRDef with PIRAccess with PIRComputeNode with PIRArgFringe {

  def within[T<:PIRNode:ClassTag](n:PIRNode) = {
    n.ancestors.collect { case cu:T => cu }.nonEmpty
  }

  def globalOf(n:PIRNode) = {
    n.collectUp[GlobalContainer]().headOption
  }

  def contextOf(n:PIRNode) = {
    n.collectUp[ComputeContext]().headOption
  }

  def ctrlsOf(container:Container) = {
    implicit val design = container.design.asInstanceOf[PIRDesign]
    import design.pirmeta._
    container.collectDown[ComputeNode]().flatMap { comp => ctrlOf.get(comp) }.toSet[Controller]
  }
}
