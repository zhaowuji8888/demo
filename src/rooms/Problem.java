package rooms;

import java.util.*;

class Problem extends RuntimeException {
    final int status;
    final String code;
    final List<String[]> conflicts;
    Problem(int status, String message) { this(status,message,Collections.emptyList()); }
    Problem(int status, String message, List<String[]> conflicts) {
        this(status,defaultCode(status),message,conflicts);
    }
    Problem(int status, String code, String message) { this(status,code,message,Collections.emptyList()); }
    private static String defaultCode(int status) {
        switch (status) {
            case 401: return "UNAUTHORIZED";
            case 403: return "FORBIDDEN";
            case 404: return "NOT_FOUND";
            case 409: return "CONFLICT";
            case 500: return "INTERNAL_ERROR";
            default: return "INVALID_REQUEST";
        }
    }
    Problem(int status,String code,String message,List<String[]> conflicts) {
        super(message);
        this.code=code;
        this.status = status;
        this.conflicts = conflicts;
    }
}
