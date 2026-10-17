package com.kalasetu.orders.service;

import java.util.regex.Pattern;

/**
 * Ids from requests are used as Firestore document ids. Restricting their characters keeps a value
 * such as {@code "abc/sub/doc"} from being interpreted as a path into another collection.
 */
final class DocumentIds {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private DocumentIds() {
    }

    static boolean isValid(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }
}
