-- 内存分析：分配调用栈动辄十几万条，JSON 压缩后存为 bytea，只在打开内存标签页时读取。
ALTER TABLE jfr_report ADD COLUMN memory BYTEA;
