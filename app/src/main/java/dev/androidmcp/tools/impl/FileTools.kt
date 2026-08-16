package dev.androidmcp.tools.impl

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.ToolCategory
import dev.androidmcp.tools.errorResult
import dev.androidmcp.tools.inputSchema
import dev.androidmcp.tools.jsonResult
import dev.androidmcp.tools.reqStr
import dev.androidmcp.tools.str
import dev.androidmcp.tools.textResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** 允许访问的根目录：应用私有 files、应用外部私有 files、公共 Download（仅读）。 */
private fun Context.allowedRoots(): List<File> = buildList {
    add(filesDir)
    getExternalFilesDir(null)?.let { add(it) }
    @Suppress("DEPRECATION")
    add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
}.map { it.canonicalFile }

@Suppress("DEPRECATION")
private fun downloadRoot(): File =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile

/**
 * 解析路径并做 canonical 越界校验。
 * 返回 (file, error)：error 非空即失败。forWrite=true 时拒绝公共 Download 目录（仅读）。
 */
private fun Context.resolvePath(path: String, forWrite: Boolean): Pair<File?, String?> {
    val file = runCatching { File(path).canonicalFile }.getOrElse {
        return null to "非法路径: $path"
    }
    val inside = allowedRoots().any { root ->
        file.path == root.path || file.path.startsWith(root.path + File.separator)
    }
    if (!inside) {
        return null to "路径越界：仅允许应用私有目录（${filesDir.absolutePath}、" +
            "${getExternalFilesDir(null)?.absolutePath}）与公共 Download 目录（仅读）"
    }
    val dl = downloadRoot()
    if (forWrite && (file.path == dl.path || file.path.startsWith(dl.path + File.separator))) {
        return null to "公共 Download 目录仅允许读取，不允许写入/删除"
    }
    return file to null
}

class ListFilesTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "list_files"
    override val description =
        "列出目录内容（名称/大小/是否目录/修改时间），目录优先排序，超过 500 条截断。" +
            "path 不传时默认为应用外部存储根目录；仅允许应用私有目录与公共 Download 目录"
    override val category = ToolCategory.FILES
    override val inputSchema: ToolSchema = inputSchema {
        string("path", "目录绝对路径，可选")
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val defaultDir = context.getExternalFilesDir(null) ?: context.filesDir
        val path = args.str("path") ?: defaultDir.absolutePath
        val (dir, error) = context.resolvePath(path, forWrite = false)
        if (error != null) return errorResult(error)
        if (dir == null) return errorResult("路径解析失败: $path")
        if (!dir.exists()) return errorResult("目录不存在: ${dir.absolutePath}")
        if (!dir.isDirectory) return errorResult("不是目录: ${dir.absolutePath}")
        val files = dir.listFiles() ?: return errorResult("无法读取目录（可能无权限）: ${dir.absolutePath}")
        val sorted = files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
        val truncated = sorted.size > 500
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return jsonResult(buildJsonObject {
            put("path", dir.absolutePath)
            put("count", if (truncated) 500 else sorted.size)
            put("truncated", truncated)
            putJsonArray("entries") {
                sorted.take(500).forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("is_dir", f.isDirectory)
                        if (!f.isDirectory) put("size", f.length())
                        put("modified", fmt.format(Date(f.lastModified())))
                    })
                }
            }
        })
    }
}

class ReadFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "read_file"
    override val description =
        "读取文本文件内容（UTF-8，超过 256KB 截断；检测到二进制返回错误）。仅允许应用私有目录与公共 Download 目录"
    override val category = ToolCategory.FILES
    override val inputSchema: ToolSchema = inputSchema {
        string("path", "文件绝对路径", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val (file, error) = context.resolvePath(args.reqStr("path"), forWrite = false)
        if (error != null) return errorResult(error)
        if (file == null) return errorResult("路径解析失败")
        if (!file.exists()) return errorResult("文件不存在: ${file.absolutePath}")
        if (!file.isFile) return errorResult("不是普通文件: ${file.absolutePath}")
        val max = 256 * 1024
        val bytes = runCatching {
            file.inputStream().use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= max) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrElse { return errorResult("读取失败（可能无权限）: ${it.message}") }
        val truncated = bytes.size > max
        val content = if (truncated) bytes.copyOf(max) else bytes
        // 二进制检测：前 8KB 内含 NUL 字节即视为二进制
        if (content.copyOf(minOf(8192, content.size)).contains(0)) {
            return errorResult("二进制文件不支持直接读取（可用 run_shell 执行 base64 等命令处理）")
        }
        return jsonResult(buildJsonObject {
            put("path", file.absolutePath)
            put("size", file.length())
            put("truncated", truncated)
            put("content", String(content, Charsets.UTF_8))
        })
    }
}

class WriteFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "write_file"
    override val description =
        "写入文本文件（UTF-8，自动创建父目录，已存在则覆盖）。仅允许应用私有目录；公共 Download 目录不可写"
    override val category = ToolCategory.FILES
    override val inputSchema: ToolSchema = inputSchema {
        string("path", "文件绝对路径", required = true)
        string("content", "要写入的文本内容", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val (file, error) = context.resolvePath(args.reqStr("path"), forWrite = true)
        if (error != null) return errorResult(error)
        if (file == null) return errorResult("路径解析失败")
        if (file.isDirectory) return errorResult("目标是目录: ${file.absolutePath}")
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(args.reqStr("content"))
            textResult("已写入 ${file.absolutePath}（${file.length()} 字节）")
        }.getOrElse { errorResult("写入失败: ${it.message}") }
    }
}

class DeleteFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : McpTool {
    override val name = "delete_file"
    override val description =
        "删除文件或空目录（非空目录会失败，不做递归删除）。仅允许应用私有目录；公共 Download 目录不可删"
    override val category = ToolCategory.FILES
    override val inputSchema: ToolSchema = inputSchema {
        string("path", "要删除的文件/空目录绝对路径", required = true)
    }

    override suspend fun execute(args: JsonObject): CallToolResult {
        val (file, error) = context.resolvePath(args.reqStr("path"), forWrite = true)
        if (error != null) return errorResult(error)
        if (file == null) return errorResult("路径解析失败")
        if (!file.exists()) return errorResult("文件不存在: ${file.absolutePath}")
        val ok = runCatching { file.delete() }.getOrDefault(false)
        return if (ok) {
            textResult("已删除 ${file.absolutePath}")
        } else {
            errorResult("删除失败（目录非空或无权限）: ${file.absolutePath}")
        }
    }
}
