package dev.gamersden.catalog.domain;

/**
 * How a cart was opened. Not a column: {@code carts.session_id} is the whole distinction — NULL
 * means a counter sale, a value means the cart belongs to a seat on the Floor (V001 DDL).
 */
public enum CartType {

    /** {@code POST /carts {type: COUNTER}} — a walk-up F&amp;B sale with no session behind it. */
    COUNTER,

    /** A cart hanging off a live session; it settles with that session's bill. */
    SESSION
}
