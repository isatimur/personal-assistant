package com.assistant.tools.email

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import jakarta.mail.Authenticator
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

class EmailTool(private val config: EmailConfig) : ToolPort {
    override val name = "email"
    override val description = "Reads and sends email. Commands: email_list(count), email_read(index), email_send(to, subject, body)"

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "email_list",
            description = "List the most recent emails from the inbox",
            params = listOf(ParamSpec("count", "integer", "Number of emails to list", required = false))
        ),
        CommandSpec(
            name = "email_read",
            description = "Read a specific email by index (0 = most recent)",
            params = listOf(ParamSpec("index", "integer", "Zero-based email index", required = false))
        ),
        CommandSpec(
            name = "email_send",
            description = "Send an email",
            params = listOf(
                ParamSpec("to", "string", "Recipient email address"),
                ParamSpec("subject", "string", "Email subject line"),
                ParamSpec("body", "string", "Email body text")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        return when (call.name) {
            "email_list" -> listEmails(call.arguments["count"]?.toString()?.toIntOrNull() ?: 10)
            "email_read" -> readEmail(call.arguments["index"]?.toString()?.toIntOrNull() ?: 0)
            "email_send" -> {
                val to = call.arguments["to"] as? String ?: return Observation.Error("Missing 'to'")
                val subject = call.arguments["subject"] as? String ?: return Observation.Error("Missing 'subject'")
                val body = call.arguments["body"] as? String ?: return Observation.Error("Missing 'body'")
                sendEmail(to, subject, body)
            }
            else -> Observation.Error("Unknown email command: ${call.name}")
        }
    }

    private fun imapProps() = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", config.imapHost)
        put("mail.imaps.port", config.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
    }

    private fun listEmails(count: Int): Observation = runCatching {
        val store = jakarta.mail.Session.getInstance(imapProps()).getStore("imaps")
        store.connect(config.imapHost, config.username, config.password)
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        val result = inbox.messages.takeLast(count)
            .mapIndexed { i, msg -> "$i: [${msg.from?.firstOrNull()}] ${msg.subject}" }
            .joinToString("\n")
        inbox.close(false); store.close()
        Observation.Success(result)
    }.getOrElse { Observation.Error(it.message ?: "Failed to list emails") }

    private fun readEmail(index: Int): Observation = runCatching {
        val store = jakarta.mail.Session.getInstance(imapProps()).getStore("imaps")
        store.connect(config.imapHost, config.username, config.password)
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        val msg = inbox.messages[inbox.messageCount - 1 - index]
        val content = when (val c = msg.content) {
            is String -> c
            is Multipart -> (0 until c.count).joinToString("\n") { c.getBodyPart(it).content.toString() }
            else -> c.toString()
        }
        inbox.close(false); store.close()
        Observation.Success("From: ${msg.from?.firstOrNull()}\nSubject: ${msg.subject}\n\n$content")
    }.getOrElse { Observation.Error(it.message ?: "Failed to read email") }

    private fun sendEmail(to: String, subject: String, body: String): Observation = runCatching {
        val props = Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }
        val session = jakarta.mail.Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(config.username, config.password)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.username))
            setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
            setText(body)
        }
        Transport.send(message)
        Observation.Success("Email sent to $to")
    }.getOrElse { Observation.Error(it.message ?: "Failed to send email") }
}
