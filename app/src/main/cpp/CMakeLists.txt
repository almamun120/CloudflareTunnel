cmake_minimum_required(VERSION 3.22.1)
project("cloudflare_tunnel")

add_library(tunnel_manager SHARED tunnel_manager.cpp)

find_library(log-lib log)
target_link_libraries(tunnel_manager ${log-lib})
