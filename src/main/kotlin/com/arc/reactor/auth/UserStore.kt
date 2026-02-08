package com.arc.reactor.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * User Store Interface
 *
 * Manages CRUD operations for user accounts.
 * Implementations must enforce email uniqueness.
 *
 * @see InMemoryUserStore for default implementation
 */
interface UserStore {

    /**
     * Find a user by email address.
     *
     * @return [User] if found, null otherwise
     */
    fun findByEmail(email: String): User?

    /**
     * Find a user by ID.
     *
     * @return [User] if found, null otherwise
     */
    fun findById(id: String): User?

    /**
     * Save a new user. Implementations should reject duplicate emails.
     *
     * @return The saved user
     * @throws IllegalArgumentException if a user with the same email already exists
     */
    fun save(user: User): User

    /**
     * Check if a user with the given email already exists.
     */
    fun existsByEmail(email: String): Boolean

    /**
     * Update an existing user.
     * Default implementation delegates to save for backward compatibility.
     *
     * @return The updated user
     */
    fun update(user: User): User = save(user)
}

/**
 * In-Memory User Store
 *
 * Thread-safe implementation using [ConcurrentHashMap].
 * Not persistent — data is lost on server restart.
 */
class InMemoryUserStore : UserStore {

    private val usersById = ConcurrentHashMap<String, User>()
    private val usersByEmail = ConcurrentHashMap<String, User>()

    override fun findByEmail(email: String): User? = usersByEmail[email]

    override fun findById(id: String): User? = usersById[id]

    override fun save(user: User): User {
        require(!usersByEmail.containsKey(user.email)) {
            "User with email ${user.email} already exists"
        }
        usersById[user.id] = user
        usersByEmail[user.email] = user
        return user
    }

    override fun existsByEmail(email: String): Boolean = usersByEmail.containsKey(email)

    override fun update(user: User): User {
        usersById[user.id] = user
        usersByEmail[user.email] = user
        return user
    }
}
