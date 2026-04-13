package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.model.ProjectCatalog
import com.letta.mobile.data.model.ProjectSummary
import io.mockk.mockk

class FakeProjectApi : ProjectApi(mockk(relaxed = true)) {
    var projects = mutableListOf<ProjectSummary>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listProjects(): ProjectCatalog {
        calls.add("listProjects")
        if (shouldFail) throw ApiException(500, "Server error")
        return ProjectCatalog(total = projects.size, projects = projects.toList())
    }

    override suspend fun getProject(identifier: String): ProjectSummary {
        calls.add("getProject:$identifier")
        if (shouldFail) throw ApiException(500, "Server error")
        return projects.firstOrNull { it.identifier == identifier }
            ?: throw ApiException(404, "Not found")
    }
}
