# Fix MatchRule Duplication When AiRoute Changes Provider

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bug where updating an AiRoute's provider leaves orphaned matchRules, causing duplicate entries in ai-proxy.internal wasmplugin.

**Architecture:** When an AiRoute changes provider, clean up all existing ai-proxy matchRules for that route before the update completes. This ensures the old provider's matchRule is removed and the new provider's matchRule is created fresh.

**Tech Stack:** Java, Spring Boot, Higress SDK

---

## Problem Summary

**复现路径:**
1. AiRoute R1 使用 Provider P1 (upstream = S1) → 创建 matchRule `[ROUTE=R1, SERVICE=S1]`
2. 用户修改 AiRoute R1，改用 Provider P2 → **但 P1 的 matchRule 未被清理！**
3. 用户更新 Provider P2 → 创建新的 matchRule `[ROUTE=R1, SERVICE=S2]`

**最终结果:** WasmPlugin 中存在两条 matchRule，但理论上只应该有一条（R1 对应一条 matchRule）。

**根因:** `AiRouteServiceImpl.update()` 只更新 Route 资源，**不清理任何 matchRule**。

---

## File Structure

- **Modify:** `backend/sdk/src/main/java/com/alibaba/higress/sdk/service/ai/AiRouteServiceImpl.java:242-250`
  - 在 `writeAiRouteResources()` 方法中，更新前清理该 route 的所有 ai-proxy matchRules

---

## Task Breakdown

### Task 1: Write Failing Test for MatchRule Cleanup

**Files:**
- Modify: `backend/sdk/src/test/java/com/alibaba/higress/sdk/service/ai/LlmProviderServiceTest.java`

- [ ] **Step 1: Add test case for orphaned matchRule cleanup**

在 `LlmProviderServiceTest.java` 中添加新测试方法，验证当 AiRoute 改变 provider 时，旧的 matchRule 被清理。

```java
@Test
void addOrUpdateShouldCleanupOrphanedMatchRulesWhenRouteProviderChanges() {
    // Setup: Route R1 uses Provider P1, which creates matchRule [ROUTE=R1, SERVICE=S1]
    // Action: Route R1 changes to use Provider P2
    // Assert: matchRule [ROUTE=R1, SERVICE=S1] is deleted, matchRule [ROUTE=R1, SERVICE=S2] is created
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LlmProviderServiceTest -pl backend/sdk`
Expected: New test FAILS (or entire test suite passes if mocking prevents real behavior)

---

### Task 2: Implement Cleanup in AiRouteServiceImpl

**Files:**
- Modify: `backend/sdk/src/main/java/com/alibaba/higress/sdk/service/ai/AiRouteServiceImpl.java:242-250`

- [ ] **Step 1: Add cleanup logic in writeAiRouteResources()**

在 `writeAiRouteResources()` 方法的**最开始**（第242行之后），添加清理逻辑：

```java
private void writeAiRouteResources(AiRoute aiRoute) {
    // NEW: Clean up all existing ai-proxy matchRules for this route before updating
    String routeName = buildRouteResourceName(aiRoute.getName());
    cleanupAiProxyMatchRules(routeName);

    // ... existing code continues ...
}
```

- [ ] **Step 2: Add helper method cleanupAiProxyMatchRules()**

在类末尾添加新方法：

```java
/**
 * Clean up all ai-proxy internal matchRules for the specified route.
 * This is called before updating route resources to ensure old provider's
 * matchRules are removed when a route changes provider.
 */
private void cleanupAiProxyMatchRules(String routeName) {
    // Delete all ai-proxy matchRules that have ROUTE scope = routeName
    wasmPluginInstanceService.deleteAll(WasmPluginInstanceScope.ROUTE, routeName);
}
```

**代码依据:**
- `WasmPluginInstanceServiceImpl.deleteAll()` (第292-308行) 可以删除所有匹配指定 scope+target 的 matchRule
- `WasmPluginInstanceScope.ROUTE` 用于匹配 ROUTE 维度的 matchRule

- [ ] **Step 3: Run tests to verify fix**

Run: `mvn test -Dtest=LlmProviderServiceTest -pl backend/sdk`
Expected: PASS

---

### Task 3: Verify with Manual Testing

- [ ] **Step 1: Build the project**

Run: `mvn clean package -DskipTests -pl backend/sdk,backend/console`

- [ ] **Step 2: Verify behavior**

Manual verification steps:
1. 创建 Provider P1 (使用 service S1)
2. 创建 AiRoute R1，关联 P1
3. 验证 ai-proxy.internal wasmplugin 中有 1 条 matchRule：`[ROUTE=<R1-internal>, SERVICE=S1]`
4. 修改 AiRoute R1，改用 Provider P2 (使用 service S2)
5. 更新 Provider P2
6. 验证 ai-proxy.internal wasmplugin 中只有 1 条 matchRule：`[ROUTE=<R1-internal>, SERVICE=S2]`
   - ✅ 正确：只有新记录
   - ❌ 错误：存在两条记录（旧记录未清理）

---

## Key Code Changes Summary

### AiRouteServiceImpl.java (修改)

**位置: 第 242-250 行**

```java
private void writeAiRouteResources(AiRoute aiRoute) {
    // NEW: Clean up all existing ai-proxy matchRules for this route before updating
    String routeName = buildRouteResourceName(aiRoute.getName());
    cleanupAiProxyMatchRules(routeName);

    Route route = buildRoute(routeName, aiRoute);
    setUpstreams(route, aiRoute.getUpstreams());
    saveRoute(route);
    writeModelRouterResources(aiRoute.getModelPredicates());
    writeModelMappingResources(routeName, aiRoute.getUpstreams());
    writeAiStatisticsResources(routeName);
}
```

**新增方法 (类末尾):**

```java
/**
 * Clean up all ai-proxy internal matchRules for the specified route.
 */
private void cleanupAiProxyMatchRules(String routeName) {
    wasmPluginInstanceService.deleteAll(WasmPluginInstanceScope.ROUTE, routeName);
}
```

---

## Verification Commands

```bash
# Run LlmProviderService tests
mvn test -Dtest=LlmProviderServiceTest -pl backend/sdk

# Run AiRouteService tests (if exists)
mvn test -Dtest=AiRouteServiceTest -pl backend/sdk

# Run all AI-related tests
mvn test -Dtest="*Ai*Test" -pl backend/sdk

# Build without tests
mvn clean package -DskipTests -pl backend/sdk,backend/console
```
