package meghanada.analyze;

import com.google.common.base.MoreObjects;
import java.io.Serializable;

public class Position implements Serializable {

  private static final long serialVersionUID = -1827615026831853860L;

  public int line;
  public int column;

  public Position(final int line, final int column) {
    this.line = line;
    this.column = column;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("l", line).add("c", column).toString();
  }
}
