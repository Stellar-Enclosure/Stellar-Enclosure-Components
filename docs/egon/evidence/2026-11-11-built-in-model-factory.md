# T4-01 Agent Flow 内置模型工厂检查源码证据

Components 基线 `a9539edf1c420132fff04024a51ee7f6d4faff2a`。[Spec](../spec/2026-11-11-16-18-built-in-model-factory.md) · [Plan](../plan/2026-11-11-16-19-built-in-model-factory.md)

| 门禁 | 状态 |
| --- | --- |
| R1/R2 | 隔离 Java 25，两次单独删除各层检查，只有 `AgentFlowFactoryTest#recognizes_only_the_builtin_compiler_and_model_factory_without_calling_overrides` 分别在 `[custom compiler]` 和 `[custom model factory]` 得到指定 AssertionFailedError。组件仓库定向 Maven 复现内层工厂反证。 |
| G1 | 组件仓库定向与干净制品各通过七项 `AgentFlowFactoryTest`，零失败、错误或跳过。 |
| P1 | 实际 JAR 类字节与编译输出一致。javap 核对公开签名和两处类身份读取。包 SHA-256 `c67a538fe22f862ba0f1392c6a28448b16ad6b386f8fd3141949d2bf33b6d447`。 |
| S1 | 严格 Spec／Plan 及五条提交路径核对通过。 |

探针保持两种覆盖计数为零，普通编译仍可调用各覆盖。组件未连接模型或启动应用，未更改依赖、普通编译与自动配置，未安装或发布 SNAPSHOT。
