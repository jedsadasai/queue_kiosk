package com.queuekiosk.data

class FakeSession(
    override var baseUrl: String = "",
    override var username: String = "",
    override var password: String = "",
    override var token: String = "",
    override var workflowId: Int = -1,
    override var workflowName: String = "",
    override var branchName: String = "",
) : SessionStore
