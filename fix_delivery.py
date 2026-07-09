import re

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopImageRestoreTest.kt", "r") as f:
    content = f.read()

content = content.replace("import com.letta.mobile.data.model.DeliveryState\n", "import com.letta.mobile.data.timeline.DeliveryState\n")

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopImageRestoreTest.kt", "w") as f:
    f.write(content)
