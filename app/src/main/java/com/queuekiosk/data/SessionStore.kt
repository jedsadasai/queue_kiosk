package com.queuekiosk.data

/**
 * What the auth/queue layer needs to persist. fleQ kept all of this in plain
 * SharedPreferences (RajthaneeSharedPreferences). [KioskPrefs] is the real implementation;
 * tests use an in-memory one.
 */
interface SessionStore {
    var baseUrl: String
    var username: String
    var password: String

    /** Full Authorization header value, e.g. "Bearer abc123" (fleQ: token_type + " " + token). */
    var token: String
    var workflowId: Int
    var workflowName: String
    var branchName: String
}
