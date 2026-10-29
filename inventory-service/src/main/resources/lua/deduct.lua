-- L5: trừ counter tồn kho cho cả đơn, all-or-nothing, nguyên tử (Redis chạy script đơn luồng).
-- KEYS[i] = inv:stock:<productId>, ARGV[i] = quantity (đã sắp theo productId)
-- Trả về {code, i}: code 1 = OK, -1 = key thứ i chưa nạp, -2 = key thứ i không đủ hàng.
-- Không bao giờ trả 0 → không nhập nhằng "0 nghĩa là gì" như v1.
for i = 1, #KEYS do
  local v = redis.call('GET', KEYS[i])
  if not v then return {-1, i} end
  if tonumber(v) < tonumber(ARGV[i]) then return {-2, i} end
end
for i = 1, #KEYS do
  redis.call('DECRBY', KEYS[i], ARGV[i])
end
return {1, 0}
