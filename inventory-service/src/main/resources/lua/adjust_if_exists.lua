-- Cộng/trừ counter CHỈ KHI key đã có. INCRBY thường sẽ tạo key mới với giá trị = delta (sai hoàn toàn) nếu
-- key vừa bị xóa/hết hạn; để key thiếu thì lần reserve sau nạp lại từ DB.
-- KEYS[1] = inv:stock:<productId>, ARGV[1] = delta. Trả về giá trị mới, hoặc nil nếu key không có.
if redis.call('EXISTS', KEYS[1]) == 1 then
  return redis.call('INCRBY', KEYS[1], ARGV[1])
end
return nil
