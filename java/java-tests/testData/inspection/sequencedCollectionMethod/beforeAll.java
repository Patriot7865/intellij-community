// "Fix all 'SequencedCollection method can be used' problems in file" "true"
import java.util.*;

interface Foo extends SequencedCollection<String> {}

public class Test {
  private static final int SOME_CONSTANT = 0;

  public static void main(Foo foo, String[] args) {
    List<String> list = List.of(args);

    var e1 = foo.iterator().n<caret>ext();
    var e2 = list.get(0);
    var e3 = list.get(list.size() - 1);
    var e4 = list.remove(0);
    var e5 = list.remove(list.size() - 1);
    list.remove("e");
    list.get(1);

    var e6 = list.get(SOME_CONSTANT);
    var e7 = list.remove(SOME_CONSTANT);
    list.add(0, "hello");
    list.add(SOME_CONSTANT, "world");
  }
  
  void testAdd(List<String> list) {
    list.add(0, "hello");
  }
  
  void testSeries(List<String> list) {
    System.out.println(list.get(0));
    System.out.println(list.get(1));
    System.out.println(list.get(2));
  }
}
