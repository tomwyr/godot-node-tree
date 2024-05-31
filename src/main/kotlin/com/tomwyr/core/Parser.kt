package com.tomwyr.core

class SceneNodesParser {
    fun parse(sceneFileContent: String): Node {
        val scenesByResourceId = sceneFileContent
            .let(::splitToSceneResourcesLines)
            .map(::extractSceneResourceParamsLine)
            .map(::parseResourceParamsLine)
            .mapNotNull(::getResourceIdToPath)
            .let(::toScenesByResourceId)

        return sceneFileContent
            .let(::splitToNodeLines)
            .map(::extractNodeParamsLine)
            .map(::parseNodeParamsLine)
            .mapNotNull(::createNodeParams)
            .let { createRootNode(it, scenesByResourceId) }
    }

    private fun splitToSceneResourcesLines(data: String): List<String> {
        return data.split("\n").filter { line ->
            line.startsWith("[ext_resource type=\"PackedScene\"") && line.endsWith("]")
        }.also(Log::parsedSceneResources)
    }

    private fun extractSceneResourceParamsLine(line: String): String {
        Log.parsingSceneResource(line)
        val pattern = "^\\[ext_resource (.*)]$".toRegex()
        val groups = pattern.find(line)?.groups?.filterNotNull() ?: emptyList()
        val paramsLine = groups.takeIf { it.size == 2 }?.last()?.value
        return paramsLine ?: throw UnexpectedResourceFormat(line)
    }


    private fun parseResourceParamsLine(paramsLine: String): Map<String, String> {
        val paramPattern = "\\w+=\".+?\"".toRegex()
        val segments = paramPattern.findAll(paramsLine).map { it.value }.toList()
        return segments.associate(::parseParamSegment)
    }

    private fun getResourceIdToPath(params: Map<String, String>): Pair<String, String>? {
        val id = params.extract("id")
        val path = params.extract("path")

        if (id == null || path == null) {
            Log.skippingResource()
            return null
        }

        return id to path
    }

    private fun toScenesByResourceId(resourceIdsToPaths: List<Pair<String, String>>): Map<String, String> {
        val duplicates = resourceIdsToPaths
            .groupBy { it.first }
            .filter { it.value.size > 1 }
            .mapValues { it.value.map { idToPath -> idToPath.second } }

        if (duplicates.isNotEmpty()) {
            throw DuplicatedSceneResources(duplicates)
        }

        return resourceIdsToPaths.toMap()
    }

    private fun splitToNodeLines(data: String): List<String> {
        return data.split("\n").filter { line ->
            line.startsWith("[node") && line.endsWith("]")
        }.also(Log::parsedSceneNodes)
    }

    private fun extractNodeParamsLine(line: String): String {
        Log.parsingNode(line)
        val pattern = "^\\[node (.*)]$".toRegex()
        val groups = pattern.find(line)?.groups?.filterNotNull() ?: emptyList()
        val paramsLine = groups.takeIf { it.size == 2 }?.last()?.value
        return paramsLine ?: throw UnexpectedNodeFormat(line)
    }

    private fun parseNodeParamsLine(paramsLine: String): Map<String, String> {
        val paramPattern = "\\w+=(\".+?\"|\\w+\\(\".+\"\\))".toRegex()
        val segments = paramPattern.findAll(paramsLine).map { it.value }.toList()
        return segments.associate(::parseParamSegment)
    }

    private fun parseParamSegment(segment: String): Pair<String, String> {
        val paramPattern = "^(.+)=(.+)$".toRegex()
        val groups = paramPattern.find(segment)?.groups?.filterNotNull() ?: emptyList()
        return groups.map { it.value }.let { it[1] to it[2] }
    }

    private fun createNodeParams(params: Map<String, String>): NodeParams? {
        val name = params.extract("name")
        val type = params.extract("type")
        val instance = params.extractResource("instance")
        val parent = params.extract("parent")

        if (name == null || (type == null && instance == null) || (type != null && instance != null)) {
            Log.skippingNode()
            return null
        }

        return NodeParams(name = name, type = type, instance = instance, parent = parent)
    }

    private fun createRootNode(params: List<NodeParams>, scenesByResourceId: Map<String, String>): Node {
        Log.creatingRootNode()
        val childrenByParent = params.groupBy { it.parent }
        val rootParams = params.single { it.parent == null }
        return rootParams.toNode(childrenByParent, scenesByResourceId)
    }

}

data class NodeParams(
    val name: String,
    val type: String?,
    val instance: String?,
    val parent: String?,
) {
    fun toNode(childrenByParent: Map<String?, List<NodeParams>>, scenesByResourceId: Map<String, String>): Node {
        return when {
            type != null && instance == null -> {
                val childrenKey = when (parent) {
                    null -> "."
                    "." -> name
                    else -> "$parent/$name"
                }

                val children = childrenByParent
                    .getOrDefault(childrenKey, emptyList())
                    .map { it.toNode(childrenByParent, scenesByResourceId) }

                SceneNode(name = name, type = type, children = children)
            }

            type == null && instance != null -> {
                val pattern = "^res://(.*).tscn$".toRegex()
                val scenePath = scenesByResourceId[instance] ?: throw UnexpectedSceneResource(instance)
                val scene = pattern.find(scenePath)?.groupValues?.getOrNull(1)?.capitalize()
                scene ?: throw UnexpectedSceneFormat(scenePath)
                NestedScene(name = name, scene = scene)
            }

            else -> throw UnexpectedNodeParameters(this)
        }
    }
}

private fun Map<String, String>.extract(key: String): String? {
    return this[key]?.trim('\"')
}

private fun Map<String, String>.extractResource(key: String): String? {
    return this[key]?.removePrefix("ExtResource(\"")?.removeSuffix("\")")
}
