package com.arc.reactor.auth

/**
 * Authentication Provider Interface
 *
 * Abstracts user authentication logic. Enterprises can provide custom implementations
 * (e.g., LDAP, SSO, OAuth2) by overriding the default bean.
 *
 * @see DefaultAuthProvider for BCrypt-based default implementation
 */
interface AuthProvider {

    /**
     * Authenticate a user by email and password.
     *
     * @return Authenticated [User] if credentials are valid, null otherwise
     */
    fun authenticate(email: String, password: String): User?

    /**
     * Look up a user by ID.
     *
     * @return [User] if found, null otherwise
     */
    fun getUserById(userId: String): User?
}
