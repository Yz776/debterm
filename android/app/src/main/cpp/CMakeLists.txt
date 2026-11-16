cmake_minimum_required(VERSION 3.22.1)
project("debterm-pty")

add_library(
    debterm-pty
    SHARED
    pty_core.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    debterm-pty
    ${log-lib}
)
