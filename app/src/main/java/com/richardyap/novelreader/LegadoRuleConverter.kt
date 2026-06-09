package com.richardyap.novelreader

import org.json.JSONArray
import org.json.JSONObject

data class LegacyConversionResult(
    val script: String,
    val warnings: List<String> = emptyList(),
    val mappedFields: List<String> = emptyList(),
)

private data class LegacyRuleSet(
    val searchUrl: String = "",
    val searchList: String = "",
    val searchName: String = "",
    val searchAuthor: String = "",
    val searchCoverUrl: String = "",
    val searchNoteUrl: String = "",
    val searchKind: String = "",
    val searchLastChapter: String = "",
    val searchIntro: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    val bookKind: String = "",
    val bookLastChapter: String = "",
    val bookIntro: String = "",
    val bookCoverUrl: String = "",
    val tocList: String = "",
    val chapterName: String = "",
    val chapterUrl: String = "",
    val content: String = "",
    val nextUrl: String = "",
)

fun convertLegacyRuleToJsSource(input: String): LegacyConversionResult {
    val trimmed = input.trim()
    require(trimmed.isNotBlank()) { "旧规则内容为空" }

    val root = parseLegacyRoot(trimmed)
    val meta = parseLegacyMeta(root)
    val rules = parseLegacyRules(root)

    val warnings = mutableListOf<String>()
    val mappedFields = mutableListOf<String>()

    fun mark(name: String, value: String) {
        if (value.isNotBlank()) mappedFields += name
    }

    mark("bookSourceName", meta.name)
    mark("bookSourceUrl", meta.url)
    mark("bookSourceGroup", meta.group)
    mark("bookSourceAuthor", meta.author)
    mark("ruleSearchUrl", rules.searchUrl)
    mark("ruleSearchList", rules.searchList)
    mark("ruleSearchName", rules.searchName)
    mark("ruleSearchAuthor", rules.searchAuthor)
    mark("ruleSearchCoverUrl", rules.searchCoverUrl)
    mark("ruleSearchNoteUrl", rules.searchNoteUrl)
    mark("ruleSearchKind", rules.searchKind)
    mark("ruleSearchLastChapter", rules.searchLastChapter)
    mark("ruleSearchIntroduce", rules.searchIntro)
    mark("ruleBookName", rules.bookName)
    mark("ruleBookAuthor", rules.bookAuthor)
    mark("ruleBookKind", rules.bookKind)
    mark("ruleBookLastChapter", rules.bookLastChapter)
    mark("ruleIntroduce", rules.bookIntro)
    mark("ruleBookCoverUrl", rules.bookCoverUrl)
    mark("ruleToc", rules.tocList)
    mark("ruleChapterName", rules.chapterName)
    mark("ruleChapterUrl", rules.chapterUrl)
    mark("ruleContent", rules.content)
    mark("ruleNextUrl", rules.nextUrl)

    if (rules.searchUrl.isBlank()) warnings += "未找到 ruleSearchUrl/searchUrl，搜索功能只会生成骨架。"
    if (rules.searchList.isBlank()) warnings += "未找到 ruleSearchList/searchList，搜索结果列表无法自动还原。"
    if (rules.searchName.isBlank()) warnings += "未找到 ruleSearchName，搜索结果书名会退回到通用提取。"
    if (rules.searchNoteUrl.isBlank()) warnings += "未找到 ruleSearchNoteUrl，搜索结果跳转链接会退回到通用提取。"
    if (rules.tocList.isBlank()) warnings += "未找到 ruleToc，目录列表只能生成骨架。"
    if (rules.chapterName.isBlank()) warnings += "未找到 ruleChapterName，章节名会退回到通用提取。"
    if (rules.chapterUrl.isBlank()) warnings += "未找到 ruleChapterUrl，章节链接会退回到通用提取。"
    if (rules.content.isBlank()) warnings += "未找到 ruleContent，正文内容只能生成骨架。"

    val jsScript = buildLegacyJsScript(meta, rules, mappedFields)

    if (containsJsRule(root)) {
        warnings += "检测到 @js: 规则，已按脚本字符串保留，但复杂脚本仍可能需要人工微调。"
    }
    if (containsHeaderRule(root)) {
        warnings += "检测到 Header 请求头规则，当前转换器会尽量保留 URL/参数，部分请求头仅做兼容性提示。"
    }
    if (containsJsonPathRule(root)) {
        warnings += "检测到 JSONPath 风格字段，已添加 JSON 抽取兼容，但仍以最佳努力方式解析。"
    }

    return LegacyConversionResult(
        script = jsScript,
        warnings = warnings.distinct(),
        mappedFields = mappedFields.distinct(),
    )
}

private fun parseLegacyRoot(input: String): JSONObject {
    return when {
        input.startsWith("{") -> JSONObject(input)
        input.startsWith("[") -> {
            val array = JSONArray(input)
            require(array.length() > 0) { "旧规则数组为空" }
            array.getJSONObject(0)
        }
        else -> error("暂不支持的旧规则格式")
    }
}

private data class LegacyMeta(
    val name: String = "",
    val url: String = "",
    val group: String = "",
    val author: String = "",
    val type: String = "novel",
    val enabled: Boolean = true,
    val description: String = "",
)

private fun parseLegacyMeta(root: JSONObject): LegacyMeta {
    return LegacyMeta(
        name = firstNonBlank(root, "bookSourceName", "name").ifBlank { "未命名旧规则" },
        url = firstNonBlank(root, "bookSourceUrl", "url"),
        group = firstNonBlank(root, "bookSourceGroup", "group"),
        author = firstNonBlank(root, "bookSourceAuthor", "author"),
        type = firstNonBlank(root, "bookSourceType", "type").ifBlank { "novel" },
        enabled = root.optBoolean("enabled", root.optBoolean("enable", true)),
        description = firstNonBlank(root, "comment", "bookSourceComment", "description", "sourceRemark"),
    )
}

private fun parseLegacyRules(root: JSONObject): LegacyRuleSet {
    return LegacyRuleSet(
        searchUrl = firstNonBlank(root, "ruleSearchUrl", "searchUrl"),
        searchList = firstNonBlank(root, "ruleSearchList", "searchList"),
        searchName = firstNonBlank(root, "ruleSearchName", "searchName"),
        searchAuthor = firstNonBlank(root, "ruleSearchAuthor", "searchAuthor"),
        searchCoverUrl = firstNonBlank(root, "ruleSearchCoverUrl", "searchCoverUrl"),
        searchNoteUrl = firstNonBlank(root, "ruleSearchNoteUrl", "searchNoteUrl"),
        searchKind = firstNonBlank(root, "ruleSearchKind", "searchKind"),
        searchLastChapter = firstNonBlank(root, "ruleSearchLastChapter", "searchLastChapter"),
        searchIntro = firstNonBlank(root, "ruleSearchIntroduce", "ruleSearchIntro", "searchIntro"),
        bookName = firstNonBlank(root, "ruleBookName", "bookName"),
        bookAuthor = firstNonBlank(root, "ruleBookAuthor", "bookAuthor"),
        bookKind = firstNonBlank(root, "ruleBookKind", "bookKind"),
        bookLastChapter = firstNonBlank(root, "ruleBookLastChapter", "bookLastChapter"),
        bookIntro = firstNonBlank(root, "ruleIntroduce", "ruleIntro", "bookIntro", "introduce"),
        bookCoverUrl = firstNonBlank(root, "ruleBookCoverUrl", "ruleCoverUrl", "bookCoverUrl", "coverUrl"),
        tocList = firstNonBlank(root, "ruleToc", "chapterListRule", "tocRule", "ruleChapterList"),
        chapterName = firstNonBlank(root, "ruleChapterName", "chapterNameRule"),
        chapterUrl = firstNonBlank(root, "ruleChapterUrl", "chapterUrlRule"),
        content = firstNonBlank(root, "ruleContent", "contentRule", "ruleBookContent"),
        nextUrl = firstNonBlank(root, "ruleNextUrl", "nextUrlRule"),
    )
}

private fun buildLegacyJsScript(meta: LegacyMeta, rules: LegacyRuleSet, mappedFields: List<String>): String {
    val header = buildList {
        add("// @name ${meta.name}")
        if (meta.url.isNotBlank()) add("// @url ${meta.url}")
        if (meta.group.isNotBlank()) add("// @group ${meta.group}")
        if (meta.author.isNotBlank()) add("// @author ${meta.author}")
        add("// @version 1")
        add("// @type ${meta.type.ifBlank { "novel" }}")
        if (meta.description.isNotBlank()) add("// @description ${meta.description.replace('\n', ' ')}")
        if (!meta.enabled) add("// @enabled false")
    }.joinToString("\n")

    return buildString {
        appendLine(header)
        appendLine()
        appendLine("var __legacy = {")
        appendLine("  searchUrl: ${jsonQuote(rules.searchUrl)},")
        appendLine("  searchList: ${jsonQuote(rules.searchList)},")
        appendLine("  searchName: ${jsonQuote(rules.searchName)},")
        appendLine("  searchAuthor: ${jsonQuote(rules.searchAuthor)},")
        appendLine("  searchCoverUrl: ${jsonQuote(rules.searchCoverUrl)},")
        appendLine("  searchNoteUrl: ${jsonQuote(rules.searchNoteUrl)},")
        appendLine("  searchKind: ${jsonQuote(rules.searchKind)},")
        appendLine("  searchLastChapter: ${jsonQuote(rules.searchLastChapter)},")
        appendLine("  searchIntro: ${jsonQuote(rules.searchIntro)},")
        appendLine("  bookName: ${jsonQuote(rules.bookName)},")
        appendLine("  bookAuthor: ${jsonQuote(rules.bookAuthor)},")
        appendLine("  bookKind: ${jsonQuote(rules.bookKind)},")
        appendLine("  bookLastChapter: ${jsonQuote(rules.bookLastChapter)},")
        appendLine("  bookIntro: ${jsonQuote(rules.bookIntro)},")
        appendLine("  bookCoverUrl: ${jsonQuote(rules.bookCoverUrl)},")
        appendLine("  tocList: ${jsonQuote(rules.tocList)},")
        appendLine("  chapterName: ${jsonQuote(rules.chapterName)},")
        appendLine("  chapterUrl: ${jsonQuote(rules.chapterUrl)},")
        appendLine("  content: ${jsonQuote(rules.content)},")
        appendLine("  nextUrl: ${jsonQuote(rules.nextUrl)},")
        appendLine("};")
        appendLine()
        appendLine("function __legacyQuote(value) {")
        appendLine("  return JSON.stringify(value == null ? '' : String(value));")
        appendLine("}")
        appendLine()
        appendLine("function __legacyTrim(value) {")
        appendLine("  return value == null ? '' : String(value).trim();")
        appendLine("}")
        appendLine()
        appendLine("function __legacyFetchTemplate(template, keyword, page, extra) {")
        appendLine("  var raw = String(template == null ? '' : template);")
        appendLine("  var parts = raw.split('@');")
        appendLine("  var url = parts.shift() || '';")
        appendLine("  var body = '';")
        appendLine("  var headers = {};")
        appendLine("  for (var i = 0; i < parts.length; i++) {")
        appendLine("    var part = String(parts[i] || '');")
        appendLine("    var lower = part.toLowerCase();")
        appendLine("    if (lower.indexOf('header:') === 0) {")
        appendLine("      var headerText = part.substring(7);")
        appendLine("      var headerItems = headerText.split(/[,;\\n]/);")
        appendLine("      for (var j = 0; j < headerItems.length; j++) {")
        appendLine("        var item = String(headerItems[j] || '').trim();")
        appendLine("        if (!item) continue;")
        appendLine("        var colon = item.indexOf(':');")
        appendLine("        if (colon > 0) {")
        appendLine("          var key = item.substring(0, colon).trim();")
        appendLine("          var value = item.substring(colon + 1).trim();")
        appendLine("          if (value === 'host' && url) {")
        appendLine("            try { value = new java.net.URI(url).getHost() || value; } catch (e) {}")
        appendLine("          }")
        appendLine("          headers[key] = value;")
        appendLine("        }")
        appendLine("      }")
        appendLine("    } else if (!body) {")
        appendLine("      body = part;")
        appendLine("    }")
        appendLine("  }")
        appendLine("  var replacements = {")
        appendLine("    searchKey: keyword == null ? '' : String(keyword),")
        appendLine("    key: keyword == null ? '' : String(keyword),")
        appendLine("    keyword: keyword == null ? '' : String(keyword),")
        appendLine("    searchPage: page == null ? '1' : String(page),")
        appendLine("    page: page == null ? '1' : String(page),")
        appendLine("  };")
        appendLine("  url = url.replace(/\\{\\s*searchKey\\s*\\}|\\{\\s*key\\s*\\}|\\{\\s*keyword\\s*\\}|searchKey|searchPage|page/g, function(token) {")
        appendLine("    return replacements[token.replace(/[\\{\\}\\s]/g, '')] || token;")
        appendLine("  });")
        appendLine("  body = body.replace(/\\{\\s*searchKey\\s*\\}|\\{\\s*key\\s*\\}|\\{\\s*keyword\\s*\\}|searchKey|searchPage|page/g, function(token) {")
        appendLine("    return replacements[token.replace(/[\\{\\}\\s]/g, '')] || token;")
        appendLine("  });")
        appendLine("  if (extra && typeof extra === 'object') {")
        appendLine("    for (var name in extra) {")
        appendLine("      if (!Object.prototype.hasOwnProperty.call(extra, name)) continue;")
        appendLine("      var value = extra[name];")
        appendLine("      var pattern = new RegExp('\\\\{\\\\s*' + name.replace(/[.*+?^${'$'}()|[\\]\\\\]/g, '\\\\\\\\$&') + '\\\\s*\\\\}', 'g');")
        appendLine("      url = url.replace(pattern, value);")
        appendLine("      body = body.replace(pattern, value);")
        appendLine("    }")
        appendLine("  }")
        appendLine("  return { url: url, body: body, headers: headers };")
        appendLine("}")
        appendLine()
        appendLine("function __legacyRequest(template, keyword, page, extra) {")
        appendLine("  var req = __legacyFetchTemplate(template, keyword, page, extra);")
        appendLine("  if (!req.body || req.body === req.url) {")
        appendLine("    return legado.getHttp().get(req.url, req.headers);")
        appendLine("  }")
        appendLine("  return legado.getHttp().post(req.url, req.body, req.headers);")
        appendLine("}")
        appendLine()
        appendLine("function __legacyParseJson(value) {")
        appendLine("  if (value == null) return null;")
        appendLine("  if (typeof value !== 'string') return value;")
        appendLine("  try { return JSON.parse(value); } catch (e) { return null; }")
        appendLine("}")
        appendLine()
        appendLine("function __legacyJsonPath(value, path) {")
        appendLine("  var current = __legacyParseJson(value);")
        appendLine("  if (!current) return null;")
        appendLine("  var clean = String(path || '').replace(/^\\$\\.?/, '');")
        appendLine("  if (!clean) return current;")
        appendLine("  clean = clean.replace(/\\[(\\d+)\\]/g, '.$1');")
        appendLine("  var parts = clean.split('.');")
        appendLine("  for (var i = 0; i < parts.length; i++) {")
        appendLine("    var part = parts[i];")
        appendLine("    if (!part) continue;")
        appendLine("    if (current == null) return null;")
        appendLine("    if (Array.isArray(current)) {")
        appendLine("      var index = parseInt(part, 10);")
        appendLine("      if (isNaN(index) || index < 0 || index >= current.length) return null;")
        appendLine("      current = current[index];")
        appendLine("    } else {")
        appendLine("      current = current[part];")
        appendLine("    }")
        appendLine("  }")
        appendLine("  return current;")
        appendLine("}")
        appendLine()
        appendLine("function __legacyLooksJsonRule(rule) {")
        appendLine("  return String(rule || '').trim().indexOf('$.') === 0;")
        appendLine("}")
        appendLine()
        appendLine("function __legacyText(value, fallback) {")
        appendLine("  if (value == null) return fallback || '';")
        appendLine("  if (typeof value === 'string') return value.trim() || (fallback || '');")
        appendLine("  try { return String(legado.getDom().text(value)).trim() || (fallback || ''); } catch (e) {}")
        appendLine("  try { return String(value).trim() || (fallback || ''); } catch (e) { return fallback || ''; }")
        appendLine("}")
        appendLine()
        appendLine("function __legacyHtml(value) {")
        appendLine("  if (value == null) return '';")
        appendLine("  if (typeof value === 'string') return value;")
        appendLine("  try { return legado.getDom().html(value); } catch (e) {}")
        appendLine("  try { return value.outerHtml ? value.outerHtml() : String(value); } catch (e) { return String(value); }")
        appendLine("}")
        appendLine()
        appendLine("function __legacySelectContext(context, selector) {")
        appendLine("  try { return legado.getDom().select(context, selector); } catch (e) { return null; }")
        appendLine("}")
        appendLine()
        appendLine("function __legacySelectContextAll(context, selector) {")
        appendLine("  try { return legado.getDom().selectAll(context, selector) || []; } catch (e) { return []; }")
        appendLine("}")
        appendLine()
        appendLine("function __legacyResolveSelector(token) {")
        appendLine("  var raw = String(token || '').trim();")
        appendLine("  if (!raw) return raw;")
        appendLine("  if (raw.indexOf('tag.') === 0) {")
        appendLine("    var tagParts = raw.split('.');")
        appendLine("    if (tagParts.length >= 3 && /^\\d+$/.test(tagParts[tagParts.length - 1])) {")
        appendLine("      return { selector: tagParts.slice(1, -1).join('.'), index: parseInt(tagParts[tagParts.length - 1], 10) };")
        appendLine("    }")
        appendLine("    return { selector: tagParts.slice(1).join('.'), index: -1 };")
        appendLine("  }")
        appendLine("  if (raw.indexOf('class.') === 0) {")
        appendLine("    var classParts = raw.split('.');")
        appendLine("    var className = classParts.slice(1).join('.');")
        appendLine("    var classIndex = -1;")
        appendLine("    if (classParts.length >= 3 && /^\\d+$/.test(classParts[classParts.length - 1])) {")
        appendLine("      classIndex = parseInt(classParts[classParts.length - 1], 10);")
        appendLine("      className = classParts.slice(1, -1).join('.');")
        appendLine("    }")
        appendLine("    return { selector: '.' + className, index: classIndex };")
        appendLine("  }")
        appendLine("  if (raw.indexOf('id.') === 0) {")
        appendLine("    var idParts = raw.split('.');")
        appendLine("    var idName = idParts.slice(1).join('.');")
        appendLine("    var idIndex = -1;")
        appendLine("    if (idParts.length >= 3 && /^\\d+$/.test(idParts[idParts.length - 1])) {")
        appendLine("      idIndex = parseInt(idParts[idParts.length - 1], 10);")
        appendLine("      idName = idParts.slice(1, -1).join('.');")
        appendLine("    }")
        appendLine("    return { selector: '#' + idName, index: idIndex };")
        appendLine("  }")
        appendLine("  return { selector: raw, index: -1 };")
        appendLine("}")
        appendLine()
        appendLine("function __legacySelect(context, token) {")
        appendLine("  var resolved = __legacyResolveSelector(token);")
        appendLine("  if (!resolved || !resolved.selector) return null;")
        appendLine("  var nodes = __legacySelectContextAll(context, resolved.selector);")
        appendLine("  if (!nodes || !nodes.length) return null;")
        appendLine("  var index = resolved.index >= 0 ? resolved.index : 0;")
        appendLine("  return nodes[index] || null;")
        appendLine("}")
        appendLine()
        appendLine("function __legacyApplyRegex(value, pattern) {")
        appendLine("  var text = __legacyText(value, '');")
        appendLine("  var rule = String(pattern || '').trim();")
        appendLine("  if (!rule) return text;")
        appendLine("  try {")
        appendLine("    return text.replace(new RegExp(rule), '');")
        appendLine("  } catch (e) {")
        appendLine("    return text;")
        appendLine("  }")
        appendLine("}")
        appendLine()
        appendLine("function __legacyLooksLikeBranch(branch) {")
        appendLine("  var raw = String(branch || '').trim();")
        appendLine("  if (!raw) return false;")
        appendLine("  if (raw.indexOf('@js:') === 0 || raw.indexOf('$.') === 0) return true;")
        appendLine("  return /^(tag\\.|class\\.|id\\.|\\.|#|\\w|\\[|\\*)/.test(raw);")
        appendLine("}")
        appendLine()
        appendLine("function __legacySplitBranches(rule) {")
        appendLine("  var raw = String(rule || '');")
        appendLine("  if (raw.indexOf('|') < 0) return [raw];")
        appendLine("  var parts = raw.split('|');")
        appendLine("  if (parts.length <= 1) return [raw];")
        appendLine("  for (var i = 0; i < parts.length; i++) {")
        appendLine("    if (!__legacyLooksLikeBranch(parts[i])) return [raw];")
        appendLine("  }")
        appendLine("  return parts;")
        appendLine("}")
        appendLine()
        appendLine("function __legacyRunJs(rule, result, baseUrl, item) {")
        appendLine("  var body = String(rule || '').replace(/^@js:\\s*/, '');")
        appendLine("  var source = '(function(){ var result = ' + __legacyQuote(result) + '; var baseUrl = ' + __legacyQuote(baseUrl) + '; var item = ' + __legacyQuote(item) + '; var doc = org.jsoup.Jsoup.parse(result, baseUrl); ' + body + ' })()';")
        appendLine("  return eval(source);")
        appendLine("}")
        appendLine()
        appendLine("function __legacyReadBranch(item, branch, baseUrl) {")
        appendLine("  var raw = String(branch || '').trim();")
        appendLine("  if (!raw) return '';")
        appendLine("  if (raw.indexOf('@js:') === 0) return __legacyRunJs(raw, __legacyHtml(item), baseUrl, item);")
        appendLine("  if (raw.indexOf('$.') === 0) return __legacyText(__legacyJsonPath(item, raw), '');")
        appendLine("  var regexPattern = '';")
        appendLine("  var hashIndex = raw.lastIndexOf('#');")
        appendLine("  var atIndex = raw.lastIndexOf('@');")
        appendLine("  if (hashIndex > atIndex) {")
        appendLine("    regexPattern = raw.substring(hashIndex + 1);")
        appendLine("    raw = raw.substring(0, hashIndex);")
        appendLine("  }")
        appendLine("  var tokens = raw.split('@');")
        appendLine("  var current = item;")
        appendLine("  for (var i = 0; i < tokens.length; i++) {")
        appendLine("    var token = String(tokens[i] || '').trim();")
        appendLine("    if (!token) continue;")
        appendLine("    var lower = token.toLowerCase();")
        appendLine("    if (lower === 'text' || lower === '@text') {")
        appendLine("      current = __legacyText(current, '');")
        appendLine("    } else if (lower === 'owntext') {")
        appendLine("      try { current = String(legado.getDom().ownText(current)).trim(); } catch (e) { current = __legacyText(current, ''); }")
        appendLine("    } else if (lower === 'html' || lower === 'outerhtml') {")
        appendLine("      current = __legacyHtml(current);")
        appendLine("    } else if (lower === 'href' || lower === 'url') {")
        appendLine("      try { current = String(legado.getDom().attr(current, 'href') || legado.getDom().attr(current, 'abs:href') || ''); } catch (e) { current = ''; }")
        appendLine("    } else if (lower === 'src') {")
        appendLine("      try { current = String(legado.getDom().attr(current, 'src') || legado.getDom().attr(current, 'abs:src') || ''); } catch (e) { current = ''; }")
        appendLine("    } else if (lower.indexOf('attr=') === 0) {")
        appendLine("      var attrName = token.substring(5);")
        appendLine("      try { current = String(legado.getDom().attr(current, attrName) || ''); } catch (e) { current = ''; }")
        appendLine("    } else if (lower.indexOf('header:') === 0) {")
        appendLine("      continue;")
        appendLine("    } else if (lower.indexOf('replace:') === 0) {")
        appendLine("      current = __legacyApplyRegex(current, token.substring(8));")
        appendLine("    } else if (lower.indexOf('json:') === 0) {")
        appendLine("      current = __legacyJsonPath(current, token.substring(5));")
        appendLine("    } else {")
        appendLine("      var next = __legacySelect(current, token);")
        appendLine("      current = next != null ? next : current;")
        appendLine("    }")
        appendLine("  }")
        appendLine("  var text = __legacyText(current, '');")
        appendLine("  if (regexPattern) text = __legacyApplyRegex(text, regexPattern);")
        appendLine("  return text;")
        appendLine("}")
        appendLine()
        appendLine("function __legacyReadField(item, rule, baseUrl, fallback) {")
        appendLine("  var raw = String(rule || '').trim();")
        appendLine("  if (!raw) return fallback || '';")
        appendLine("  var branches = __legacySplitBranches(raw);")
        appendLine("  for (var i = 0; i < branches.length; i++) {")
        appendLine("    var value = __legacyReadBranch(item, branches[i], baseUrl);")
        appendLine("    if (value != null && String(value).trim()) return String(value).trim();")
        appendLine("  }")
        appendLine("  return fallback || '';")
        appendLine("}")
        appendLine()
        appendLine("function __legacyItems(result, baseUrl, rule) {")
        appendLine("  var raw = String(rule || '').trim();")
        appendLine("  if (!raw) return [];")
        appendLine("  if (raw.indexOf('@js:') === 0) {")
        appendLine("    var jsResult = __legacyRunJs(raw, result, baseUrl, null);")
        appendLine("    return Array.isArray(jsResult) ? jsResult : (jsResult ? [jsResult] : []);")
        appendLine("  }")
        appendLine("  if (raw.indexOf('$.') === 0) {")
        appendLine("    var jsonItems = __legacyJsonPath(result, raw);")
        appendLine("    if (Array.isArray(jsonItems)) return jsonItems;")
        appendLine("    if (jsonItems == null) return [];")
        appendLine("    return [jsonItems];")
        appendLine("  }")
        appendLine("  var doc = legado.getDom().parse(String(result || ''), baseUrl);")
        appendLine("  return __legacySelectContextAll(doc, raw);")
        appendLine("}")
        appendLine()
        appendLine("function __legacyRequestUrl(keyword, page) {")
        appendLine("  return __legacyFetchTemplate(__legacy.searchUrl, keyword, page, null);")
        appendLine("}")
        appendLine()
        appendLine("function search(keyword, page) {")
        appendLine("  var request = __legacyRequestUrl(keyword, page);")
        appendLine("  var result = request.body && request.body !== request.url ? legado.getHttp().post(request.url, request.body, request.headers) : legado.getHttp().get(request.url, request.headers);")
        appendLine("  var items = __legacyItems(result, request.url, __legacy.searchList);")
        appendLine("  var list = [];")
        appendLine("  for (var i = 0; i < items.length; i++) {")
        appendLine("    var item = items[i];")
        appendLine("    list.push({")
        appendLine("      name: __legacyReadField(item, __legacy.searchName, request.url, __legacyText(item, '')),")
        appendLine("      bookUrl: __legacyReadField(item, __legacy.searchNoteUrl, request.url, request.url),")
        appendLine("      author: __legacyReadField(item, __legacy.searchAuthor, request.url, ''),")
        appendLine("      coverUrl: __legacyReadField(item, __legacy.searchCoverUrl, request.url, ''),")
        appendLine("      intro: __legacyReadField(item, __legacy.searchIntro, request.url, ''),")
        appendLine("      kind: __legacyReadField(item, __legacy.searchKind, request.url, ''),")
        appendLine("      latestChapter: __legacyReadField(item, __legacy.searchLastChapter, request.url, ''),")
        appendLine("      tocUrl: __legacyReadField(item, __legacy.searchNoteUrl, request.url, ''),")
        appendLine("    });")
        appendLine("  }")
        appendLine("  return list;")
        appendLine("}")
        appendLine()
        appendLine("function bookInfo(bookUrl) {")
        appendLine("  var result = legado.getHttp().get(bookUrl);")
        appendLine("  var baseUrl = bookUrl;")
        appendLine("  var item = result;")
        appendLine("  var doc = legado.getDom().parse(result, baseUrl);")
        appendLine("  return {")
        appendLine("    name: __legacyReadField(doc, __legacy.bookName, baseUrl, __legacyReadField(doc, __legacy.searchName, baseUrl, '')),")
        appendLine("    author: __legacyReadField(doc, __legacy.bookAuthor, baseUrl, __legacyReadField(doc, __legacy.searchAuthor, baseUrl, '')),")
        appendLine("    kind: __legacyReadField(doc, __legacy.bookKind, baseUrl, __legacyReadField(doc, __legacy.searchKind, baseUrl, '')),")
        appendLine("    latestChapter: __legacyReadField(doc, __legacy.bookLastChapter, baseUrl, __legacyReadField(doc, __legacy.searchLastChapter, baseUrl, '')),")
        appendLine("    intro: __legacyReadField(doc, __legacy.bookIntro, baseUrl, __legacyReadField(doc, __legacy.searchIntro, baseUrl, '')),")
        appendLine("    coverUrl: __legacyReadField(doc, __legacy.bookCoverUrl, baseUrl, __legacyReadField(doc, __legacy.searchCoverUrl, baseUrl, '')),")
        appendLine("    bookUrl: bookUrl,")
        appendLine("    tocUrl: bookUrl")
        appendLine("  };")
        appendLine("}")
        appendLine()
        appendLine("function chapterList(tocUrl) {")
        appendLine("  var result = legado.getHttp().get(tocUrl);")
        appendLine("  var items = __legacyItems(result, tocUrl, __legacy.tocList);")
        appendLine("  var list = [];")
        appendLine("  for (var i = 0; i < items.length; i++) {")
        appendLine("    var item = items[i];")
        appendLine("    list.push({")
        appendLine("      name: __legacyReadField(item, __legacy.chapterName, tocUrl, __legacyText(item, '')),")
        appendLine("      url: __legacyReadField(item, __legacy.chapterUrl, tocUrl, tocUrl),")
        appendLine("    });")
        appendLine("  }")
        appendLine("  return list;")
        appendLine("}")
        appendLine()
        appendLine("function chapterContent(chapterUrl) {")
        appendLine("  var result = legado.getHttp().get(chapterUrl);")
        appendLine("  var baseUrl = chapterUrl;")
        appendLine("  var item = result;")
        appendLine("  if (__legacy.content && __legacy.content.trim().indexOf('@js:') === 0) {")
        appendLine("    return __legacyRunJs(__legacy.content, result, baseUrl, item);")
        appendLine("  }")
        appendLine("  if (__legacy.content && __legacy.content.trim().indexOf('$.') === 0) {")
        appendLine("    return __legacyText(__legacyJsonPath(result, __legacy.content), '');")
        appendLine("  }")
        appendLine("  var doc = legado.getDom().parse(result, baseUrl);")
        appendLine("  return __legacyReadField(doc, __legacy.content, baseUrl, __legacyText(doc, ''));")
        appendLine("}")
        appendLine()
        appendLine("// Legacy rules preserved for manual completion.")
        appendLine("// Mapped fields: ${mappedFields.joinToString(", ")}")
        if (rules.searchUrl.isNotBlank()) appendLine("// ruleSearchUrl: ${rules.searchUrl.replace('\n', ' ')}")
        if (rules.searchList.isNotBlank()) appendLine("// ruleSearchList: ${rules.searchList.replace('\n', ' ')}")
        if (rules.tocList.isNotBlank()) appendLine("// ruleToc: ${rules.tocList.replace('\n', ' ')}")
        if (rules.content.isNotBlank()) appendLine("// ruleContent: ${rules.content.replace('\n', ' ')}")
    }
}

private fun containsJsRule(root: JSONObject): Boolean {
    val keys = root.keys().asSequence().toList()
    return keys.any { key -> root.optString(key, "").contains("@js:", ignoreCase = false) }
}

private fun containsHeaderRule(root: JSONObject): Boolean {
    val keys = root.keys().asSequence().toList()
    return keys.any { key -> root.optString(key, "").contains("Header:", ignoreCase = true) }
}

private fun containsJsonPathRule(root: JSONObject): Boolean {
    val keys = root.keys().asSequence().toList()
    return keys.any { key -> root.optString(key, "").contains("$.") }
}

private fun firstNonBlank(root: JSONObject, vararg names: String): String {
    for (name in names) {
        val value = root.optString(name, "").trim()
        if (value.isNotBlank()) return value
    }
    return ""
}
