# 真实无记忆基线交付物

这些文件由真实Agent运行生成，不是验收器参考答案。每个目录的manifest记录run ID与SHA-256，verification.log.txt为独立可信验收输出。任务来源与固定提交见 ../baseline-results.json：Aider-AI/polyglot-benchmark 的phone-number，以及harbor-framework/terminal-bench-2的kv-store-grpc。原始测试/参考解保留在私有fixture目录，没有作为Agent交付物发布。

每组合仅一个纳入样本。历史401与部分有额外smoke重叠的尝试保留在其他结果文件中；不据此声称稳定成功率或记忆收益。
