package com.assistant.tools.email
data class EmailConfig(val imapHost: String, val imapPort: Int, val smtpHost: String, val smtpPort: Int, val username: String, val password: String)
