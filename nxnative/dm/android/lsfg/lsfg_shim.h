// SPDX-License-Identifier: GPL-3.0-or-later
// What lsfg_chain.c took from Banana Native's Vulkan backend, in this backend's names
#pragma once
#include "../gfx_vk.h"
#include <pthread.h>
extern pthread_mutex_t g_vk_queue_mu;
#define VKA_DECL(f) PFN_##f f;
#define VRED(fmt, ...) VKLOG("[RED] " fmt, ##__VA_ARGS__)
#define VLOG(...) VKLOG(__VA_ARGS__)
static inline void lsfg_barrier(VkCommandBuffer cb, VkImage img, VkImageAspectFlags aspect, VkImageLayout from, VkImageLayout to) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, from, to, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, img, {aspect, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 0, NULL, 1, &b);
}
