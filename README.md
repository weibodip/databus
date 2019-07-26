# 部署

## 配置文件


## 启动


## 停止



# FileSource

## 约定

1. 文件名称必须唯一

1. 仅支持原始日志文件传输，不支持压缩文件传输

1. 仅支持已经Roll完成的文件传输，不支持正在写入的文件传输

## 数据结构

1. map存放正在读或已读完的所有未过期文件，map中的数据定时刷到磁盘

1. queue存放未读或未读完的文件

## 处理流程

1. 加载offset文件，

1. fileScanner线程池定时扫描未读文件放到queue中

1. fileReader线程池从queue中获取文件并处理，一个文件由一个线程处理

1. offsetRecorder线程池定时把文件读取位置刷到磁盘

## 程序规则

1. 根据日志保留时间定时调度删除过期文件

1. 文件读取策略：优先读旧文件/优先读新文件，默认优先读旧文件

1. 同一个目录下，一个正则对应一个Category


