# Console matchRules 支持 ingress 字段改动计划

## 背景

当两个 AI Provider 的 `openaiCustomServiceName` 指向同一个 service 时，后写入的 provider 会覆盖前一个，因为 matchRule 仅使用 SERVICE 维度，导致其中一个 Provider 永远无法被匹配到。

## 修改文件

**唯一修改文件**：`backend/sdk/src/main/java/com/alibaba/higress/sdk/service/ai/LlmProviderServiceImpl.java`

## 修改详情

### 改动 1：`addOrUpdate()` 方法（第 179-209 行）

**目的**：当 Provider 关联了 AiRoute 时，使用 ROUTE + SERVICE 组合维度生成 matchRule，避免 service 冲突。

**修改内容**：

将原有的：
```java
UpstreamService upstreamService = handler.buildUpstreamService(provider.getName(), providerConfig);

WasmPluginInstance existedServiceInstance = instances.stream()
    .filter(i -> i.hasScopedTarget(WasmPluginInstanceScope.SERVICE, upstreamService.getName())).findFirst()
    .orElse(null);
if (existedServiceInstance != null) {
    String boundProviderName =
        MapUtils.getString(existedServiceInstance.getConfigurations(), ACTIVE_PROVIDER_ID);
    if (!provider.getName().equals(boundProviderName)) {
        throw new ValidationException("The service instance for provider " + boundProviderName
            + " is already existed. Cannot bind it to provider " + provider.getName());
    }
}

WasmPluginInstance serviceInstance = new WasmPluginInstance();
serviceInstance.setPluginName(instance.getPluginName());
serviceInstance.setPluginVersion(instance.getPluginVersion());
serviceInstance.setTarget(WasmPluginInstanceScope.SERVICE, upstreamService.getName());
serviceInstance.setEnabled(true);
serviceInstance.setInternal(true);
serviceInstance.setConfigurations(MapUtil.of(ACTIVE_PROVIDER_ID, provider.getName()));

// Perform all the updates here just to avoid possible errors in resource building.
if (!serviceSources.isEmpty()) {
    for (ServiceSource serviceSource : serviceSources) {
        serviceSource.setProxyName(provider.getProxyName());
        serviceSourceService.addOrUpdate(serviceSource);
    }
}
wasmPluginInstanceService.addOrUpdate(instance);
wasmPluginInstanceService.addOrUpdate(serviceInstance);
```

**改为**：
```java
UpstreamService upstreamService = handler.buildUpstreamService(provider.getName(), providerConfig);

// Perform all the updates here just to avoid possible errors in resource building.
if (!serviceSources.isEmpty()) {
    for (ServiceSource serviceSource : serviceSources) {
        serviceSource.setProxyName(provider.getProxyName());
        serviceSourceService.addOrUpdate(serviceSource);
    }
}
wasmPluginInstanceService.addOrUpdate(instance);

// 查找该 Provider 关联的所有 AiRoute
List<String> relatedRouteNames = getRelatedRouteNames(provider.getName());

if (relatedRouteNames.isEmpty()) {
    // 没有关联路由时，保持原有 SERVICE 维度的 matchRule（向后兼容）
    WasmPluginInstance existedServiceInstance = instances.stream()
        .filter(i -> i.hasScopedTarget(WasmPluginInstanceScope.SERVICE, upstreamService.getName())).findFirst()
        .orElse(null);
    if (existedServiceInstance != null) {
        String boundProviderName =
            MapUtils.getString(existedServiceInstance.getConfigurations(), ACTIVE_PROVIDER_ID);
        if (!provider.getName().equals(boundProviderName)) {
            throw new ValidationException("The service instance for provider " + boundProviderName
                + " is already existed. Cannot bind it to provider " + provider.getName());
        }
    }

    WasmPluginInstance serviceInstance = new WasmPluginInstance();
    serviceInstance.setPluginName(instance.getPluginName());
    serviceInstance.setPluginVersion(instance.getPluginVersion());
    serviceInstance.setTarget(WasmPluginInstanceScope.SERVICE, upstreamService.getName());
    serviceInstance.setEnabled(true);
    serviceInstance.setInternal(true);
    serviceInstance.setConfigurations(MapUtil.of(ACTIVE_PROVIDER_ID, provider.getName()));
    wasmPluginInstanceService.addOrUpdate(serviceInstance);
} else {
    // 有关联路由时，按 ROUTE + SERVICE 组合生成 matchRule，避免 service 冲突
    for (String routeName : relatedRouteNames) {
        WasmPluginInstance routeServiceInstance = new WasmPluginInstance();
        routeServiceInstance.setPluginName(instance.getPluginName());
        routeServiceInstance.setPluginVersion(instance.getPluginVersion());
        routeServiceInstance.setTargets(MapUtil.of(
            WasmPluginInstanceScope.ROUTE, routeName,
            WasmPluginInstanceScope.SERVICE, upstreamService.getName()));
        routeServiceInstance.setEnabled(true);
        routeServiceInstance.setInternal(true);
        routeServiceInstance.setConfigurations(MapUtil.of(ACTIVE_PROVIDER_ID, provider.getName()));
        wasmPluginInstanceService.addOrUpdate(routeServiceInstance);
    }
    // 清理旧的纯 SERVICE 维度 matchRule（如果存在）
    wasmPluginInstanceService.delete(
        WasmPluginInstanceScope.SERVICE, upstreamService.getName(),
        BuiltInPluginName.AI_PROXY, true);
}
```

### 改动 2：`delete()` 方法（第 278-280 行）

**目的**：删除 Provider 时，清理 ROUTE + SERVICE 组合维度的 matchRule。

**修改内容**：

将原有的：
```java
UpstreamService upstreamService = handler.buildUpstreamService(providerName, deletedProvider);
wasmPluginInstanceService.delete(WasmPluginInstanceScope.SERVICE, upstreamService.getName(),
    BuiltInPluginName.AI_PROXY, true);
```

**改为**：
```java
UpstreamService upstreamService = handler.buildUpstreamService(providerName, deletedProvider);
// 清理纯 SERVICE 维度的 matchRule
wasmPluginInstanceService.delete(WasmPluginInstanceScope.SERVICE, upstreamService.getName(),
    BuiltInPluginName.AI_PROXY, true);
// 清理 ROUTE + SERVICE 组合维度的 matchRule
List<String> relatedRouteNames = getRelatedRouteNames(providerName);
for (String routeName : relatedRouteNames) {
    Map<WasmPluginInstanceScope, String> targets = MapUtil.of(
        WasmPluginInstanceScope.ROUTE, routeName,
        WasmPluginInstanceScope.SERVICE, upstreamService.getName());
    wasmPluginInstanceService.delete(targets, BuiltInPluginName.AI_PROXY, true);
}
```

### 改动 3：新增辅助方法 `getRelatedRouteNames()`

**目的**：查找与指定 Provider 关联的所有 AiRoute 的路由资源名称。

**位置**：在 `LlmProviderServiceImpl` 类中，`syncRelatedAiRoutes()` 方法之后新增。

**代码**：
```java
/**
 * 查找与指定 Provider 关联的所有 AiRoute 的路由资源名称。
 * 路由资源名称格式与 AiRouteServiceImpl.buildRouteResourceName() 一致。
 */
private List<String> getRelatedRouteNames(String providerName) {
    List<String> routeNames = new ArrayList<>();
    AiRouteService aiRouteService = this.aiRouteService;
    if (aiRouteService == null) {
        return routeNames;
    }
    PaginatedResult<AiRoute> allRoutes = aiRouteService.list(new CommonPageQuery());
    if (allRoutes == null || CollectionUtils.isEmpty(allRoutes.getData())) {
        return routeNames;
    }
    for (AiRoute route : allRoutes.getData()) {
        if (CollectionUtils.isEmpty(route.getUpstreams())) {
            continue;
        }
        boolean hasProvider = route.getUpstreams().stream()
            .anyMatch(u -> providerName.equals(u.getProvider()));
        if (hasProvider) {
            routeNames.add(CommonKey.AI_ROUTE_PREFIX + route.getName()
                + HigressConstants.INTERNAL_RESOURCE_NAME_SUFFIX);
        }
    }
    return routeNames;
}
```

## 需要新增的 import

在文件头部已存在的 import（无需新增）：
- `java.util.ArrayList` ✓
- `java.util.List` ✓
- `java.util.Map` ✓
- `com.alibaba.higress.sdk.model.ai.AiRoute` ✓
- `com.alibaba.higress.sdk.constant.CommonKey` - 需要确认是否存在
- `com.alibaba.higress.sdk.constant.HigressConstants` ✓

## 兼容性

| 场景 | 改动前 | 改动后 |
|------|--------|--------|
| Provider 未关联任何 AiRoute | 纯 SERVICE 维度 | 纯 SERVICE 维度（不变） |
| Provider 关联了 AiRoute，service 不冲突 | 纯 SERVICE 维度 | ROUTE + SERVICE 组合 |
| Provider 关联了 AiRoute，service 冲突 | 后写入覆盖前一个（bug） | 各自独立匹配 |
| 已有纯 SERVICE 维度的旧 matchRule | - | 自动清理 |

## 验证方式

1. 单元测试：暂无针对 `addOrUpdate` / `delete` 的直接测试用例
2. 手动测试：
   - 创建两个 Provider A 和 B，使用相同的 service但不同的 AiRoute
   - 验证两个 Provider 都能被正确匹配
   - 删除其中一个 Provider，验证相关 matchRule 被正确清理
