package com.ofss.risk.persistence;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** Internal signal, recognized only around the parent INSERT execution. */
final class SubmissionCollision extends RuntimeException {
    private static final String ID = "(\"[^\"]+\"|[A-Za-z][A-Za-z0-9_$#]*)";
    private static final Pattern IDENTITY = Pattern.compile("^\\s*ORA-00001:[^\\r\\n(]*\\(\\s*" + ID + "\\s*\\.\\s*" + ID + "\\s*\\)");
    static boolean matches(SQLException failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>(); queue.add(failure);
        boolean recognized = false;
        while (!queue.isEmpty()) {
            var item = queue.removeFirst();
            if (!visited.add(item)) continue;
            if (item instanceof SQLException sql) {
                if (sql.getErrorCode() == 1) {
                    var m = IDENTITY.matcher(sql.getMessage() == null ? "" : sql.getMessage());
                    if (!m.find() || !"D2_RISK".equals(identifier(m.group(1)))
                            || !"UK_RSK_SUBMISSION".equals(identifier(m.group(2)))) return false;
                    recognized = true;
                }
                if (sql.getNextException() != null) queue.addLast(sql.getNextException());
            }
            if (item.getCause() != null) queue.addLast(item.getCause());
        }
        return recognized;
    }
    private static String identifier(String value) {
        return value.startsWith("\"") ? value.substring(1, value.length() - 1) : value.toUpperCase(Locale.ROOT);
    }
}
