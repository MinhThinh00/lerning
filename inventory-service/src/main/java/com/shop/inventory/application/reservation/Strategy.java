package com.shop.inventory.application.reservation;

/** 5 cơ chế chống oversell (docs/04 §1). Phase 5 mới có L1; L2–L5 làm ở Phase 8. */
public enum Strategy {
    /** Conditional UPDATE ... WHERE available >= qty. */
    L1,
    /** SELECT ... FOR UPDATE. */
    L2,
    /** @Version + retry. */
    L3,
    /** Redisson lock + @Version làm fencing. */
    L4,
    /** Redis Lua counter làm cổng + L1. */
    L5
}
