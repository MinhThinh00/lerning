package com.shop.inventory.application.reservation;

/** 5 cơ chế chống oversell (docs/04 §1). Cả 5 đều đúng khi chạy N replica; khác nhau ở throughput và tải lên DB. */
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
