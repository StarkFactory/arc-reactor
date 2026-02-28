package com.arc.reactor.scheduler

/** Sends a message to a Microsoft Teams channel via Incoming Webhook. */
fun interface TeamsMessageSender {
    fun sendMessage(webhookUrl: String, text: String)
}
