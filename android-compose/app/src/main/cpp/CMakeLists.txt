cmake_minimum_required(VERSION 3.22.1)

project(lettacode_node_bridge)

add_library(libnode SHARED IMPORTED)
set_target_properties(
    libnode
    PROPERTIES
    IMPORTED_LOCATION "${LETTACODE_LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so"
    INTERFACE_INCLUDE_DIRECTORIES "${LETTACODE_LIBNODE_DIR}/include"
)

add_library(lettacode_node_bridge SHARED lettacode_node_bridge.cpp)

target_link_libraries(
    lettacode_node_bridge
    libnode
    log
)
