import java.util.Objects;

public class Foo {
    void m(String a) {
      if (Objects.nonNull(a).not<caret>)
    }
}