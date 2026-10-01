# R8 规则：存档走 Java 序列化（state.ser），类名/字段名/serialVersionUID 必须稳定
-keep class com.happyagent.mobile.model.** { *; }
-keep class com.happyagent.mobile.data.AgentBackend$State { *; }
