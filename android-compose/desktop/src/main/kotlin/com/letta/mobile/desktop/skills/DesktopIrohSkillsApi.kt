package com.letta.mobile.desktop.skills

import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohSkillsApi
import com.letta.mobile.data.skills.SkillsApi

class DesktopIrohSkillsApi(
    directory: IrohAdminRpcAgentDirectory,
) : SkillsApi by IrohSkillsApi(directory)
