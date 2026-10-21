# Module core:data

Room persistence shared by Android and JVM hosts. This first extraction owns entity metadata, DAO contracts and their model conversions; the Android shell still owns production database construction, migrations and stores. It does not provide browser storage or depend on host UI or dependency-injection frameworks.

# Package com.letta.mobile.data.local

The agents, conversations, canvas-operation journal, bug-report and runtime-event entities and DAO contracts. Preserve table/column names and indexes when moving declarations; schema history remains in `core/android-data/schemas` until production database extraction. Hosts obtain DAOs from their database builder. Keep Android `Context`, Hilt, filesystem ownership locks and driver construction outside this package's common sources.
