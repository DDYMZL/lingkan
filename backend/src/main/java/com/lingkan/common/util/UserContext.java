package com.lingkan.common.util;

/**
 * 用户上下文：第一版为单用户使用，但仍保留 user_id 维度。
 *
 * <p>小程序端通过持久化的 X-LingKan-User 头传递本地用户标识，不做注册登录。
 */
public final class UserContext {

    public static final String USER_HEADER = "X-LingKan-User";
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String userId) {
        HOLDER.set(userId);
    }

    public static String get(String defaultUserId) {
        String userId = HOLDER.get();
        return userId == null || userId.isBlank() ? defaultUserId : userId;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
