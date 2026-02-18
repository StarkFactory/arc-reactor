package com.arc.reactor.agent.impl

/**
 * Exception thrown when a classified intent is in the blocked list.
 */
class BlockedIntentException(
    val intentName: String
) : Exception("Intent '$intentName' is blocked by policy")
