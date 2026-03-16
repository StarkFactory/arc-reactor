package com.arc.reactor.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * 사용자 저장소 인터페이스
 *
 * 사용자 계정의 CRUD 작업을 관리한다.
 * 구현체는 이메일 고유성을 보장해야 한다.
 *
 * @see InMemoryUserStore 메모리 기반 기본 구현체
 * @see JdbcUserStore JDBC 기반 영구 저장 구현체
 */
interface UserStore {

    /**
     * 이메일 주소로 사용자를 찾는다.
     *
     * @return 찾은 경우 [User], 아니면 null
     */
    fun findByEmail(email: String): User?

    /**
     * ID로 사용자를 찾는다.
     *
     * @return 찾은 경우 [User], 아니면 null
     */
    fun findById(id: String): User?

    /**
     * 새 사용자를 저장한다. 구현체는 이메일 중복을 거부해야 한다.
     *
     * @return 저장된 사용자
     * @throws IllegalArgumentException 같은 이메일의 사용자가 이미 존재하는 경우
     */
    fun save(user: User): User

    /**
     * 주어진 이메일의 사용자가 이미 존재하는지 확인한다.
     */
    fun existsByEmail(email: String): Boolean

    /**
     * 기존 사용자를 업데이트한다.
     * 하위 호환성을 위해 기본 구현은 save에 위임한다.
     *
     * @return 업데이트된 사용자
     */
    fun update(user: User): User = save(user)

    /**
     * 등록된 전체 사용자 수를 반환한다.
     * 회원가입 시 첫 번째 사용자를 ADMIN으로 자동 설정하는 정책에 사용된다.
     */
    fun count(): Long
}

/**
 * 메모리 기반 사용자 저장소
 *
 * [ConcurrentHashMap]을 사용한 스레드 안전 구현체이다.
 * 영구적이지 않다 — 서버 재시작 시 데이터가 유실된다.
 *
 * @see JdbcUserStore 영구 저장이 필요한 프로덕션 환경용
 */
class InMemoryUserStore : UserStore {

    /** ID → User 매핑 */
    private val usersById = ConcurrentHashMap<String, User>()
    /** email → User 매핑 (이메일 고유성 보장) */
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

    override fun count(): Long = usersById.size.toLong()
}
