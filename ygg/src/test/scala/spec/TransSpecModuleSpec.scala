package ygg.tests

import ygg.table._

class TransSpecModuleSpec extends quasar.Qspec with TransSpecModule {
  import trans._
  import CPath._

  implicit def liftF1(f1: CF1): CF1Like = ???
  implicit def liftF2(f2: CF2): CF2Like = ???

  "concatChildren" should {
    "transform a CPathTree into a TransSpec" in {
      val tree: CPathTree[Int] = RootNode(
        Seq(
          FieldNode(
            CPathField("bar"),
            Seq(
              IndexNode(CPathIndex(0), Seq(LeafNode(4))),
              IndexNode(CPathIndex(1), Seq(FieldNode(CPathField("baz"), Seq(LeafNode(6))))),
              IndexNode(CPathIndex(2), Seq(LeafNode(2))))),
          FieldNode(CPathField("foo"), Seq(LeafNode(0)))))

      val result = TransSpec.concatChildren(tree)

      val expected = InnerObjectConcat(
        WrapObject(
          InnerArrayConcat(
            InnerArrayConcat(
              WrapArray(root(4)),
              WrapArray(WrapObject(root(6), "baz"))
            ),
            WrapArray(root(2))
          ),
          "bar"
        ),
        WrapObject(root(0), "foo")
      )

      result mustEqual expected
    }
  }
}
