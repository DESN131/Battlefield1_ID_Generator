# EAID_Generator (高性能命令行版)

一个用于 **Battlefield 1 中文 ID 查询** 的工具。  
给定一个 EAID 模式（含通配符 `@`）和目标 Hash，本工具会暴力枚举可能的 ID，并匹配到对应的中文 ID。

原项目带有 Swing GUI，本版本经过优化，提供了 **高性能命令行版**：

- 多线程并行计算（默认等于 CPU 核心数）
- 位运算替代除/模，性能显著提升
- 批量计数，减少原子竞争
- 命中结果可保存到文本文件
- 控制台实时显示进度、速度、预计剩余时间

---

## 使用说明

### 编译

```bash
javac -encoding UTF-8 -d out MainFast.java
```

### 运行
```bash
java -cp out MainFast "<EAID 模式>" "<目标Hash(十六进制)或0>" [--threads=N] [--name-file=path/to/name.txt]
```

### 参数说明

- <EAID 模式>
EAID 字符串，使用 @ 作为通配符。
例如：Satori_@@@@@@@

- <目标Hash>
八位十六进制字符串，例如：7D543A64
如果填 0，表示匹配任意中文 ID（会查 name.txt）。

- --threads=N (可选)
指定线程数，默认等于 CPU 核心数。

- --name-file=path/to/name.txt (可选)
指定中文映射文件路径（仅在 <目标Hash> 为 0 时需要）。

### 示例
```bash
# 精确匹配 Hash
java -cp out MainFast "Satori_@@@@@@@" "7D543A64"

# 匹配任意中文 ID（需要 name.txt）
java -cp out MainFast "@@_Koishi_@@" 0 --name-file=./name.txt
```

### 输出结果

程序会在控制台打印：

- 实时进度（每秒更新一次）

- 当前速度（候选/秒）

- 预计剩余时间

- 命中结果（EAID -> 中文名）

### 保存到文件

运行时加重定向即可：
```bash
java -cp out MainFast "Satori_@@@@@@@" "7D543A64" > result.txt
```

如果要同时在控制台显示并保存文件：
```bash
java -cp out MainFast "Satori_@@@@@@@" "7D543A64" | Tee-Object -FilePath result.txt
```
### 性能优化

相比 GUI 版本，本工具做了以下优化：

- 移除 Swing 界面，避免 UI 阻塞

- 使用 LongAdder + 批量合并计数，降低原子竞争

- 位运算 (& 63, >>> 6) 替代 %64 与 /64

- 预计算权重与字符差值

- 多线程分块任务

- 在常见配置下，速度可达到 每秒亿级候选。

### 限制与注意事项

- EAID 最长 22 个字符

- 通配符数量 ≥ 11 时搜索空间过大（64^11 > Long.MAX_VALUE），程序会提示

- name.txt 文件必须符合以下格式：
```
7D543A64,"皇帝"
1234ABCD,"测试"
...
```
### 致谢

- 原项目 EAID_Generator