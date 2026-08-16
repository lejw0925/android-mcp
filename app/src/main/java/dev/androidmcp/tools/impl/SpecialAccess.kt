package dev.androidmcp.tools.impl

/** 标记接口：工具依赖通知监听服务（NlService 已授权并运行）。ToolsScreen 据此展示「去授权通知使用权」chip。 */
interface RequiresNotificationAccess

/** 标记接口：工具依赖勿扰（DND）策略访问权限。ToolsScreen 据此展示「去授权勿扰权限」chip。 */
interface RequiresDndAccess
