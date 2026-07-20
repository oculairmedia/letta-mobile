package com.letta.mobile.architecture.fixtures.violation.cycle.one

import com.letta.mobile.architecture.fixtures.violation.cycle.two.CycleTwo

class CycleOne(private val other: CycleTwo)
