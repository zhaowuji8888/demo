package rooms;

import java.util.*;

class Problem extends RuntimeException {
    final int status;
    final List<String[]> conflicts;
    Problem(int status, String message) { this(status,message,Collections.emptyList()); }
    Problem(int status, String message, List<String[]> conflicts) {
        super(message);
        this.status = status;
        this.conflicts = conflicts;
    }
}
