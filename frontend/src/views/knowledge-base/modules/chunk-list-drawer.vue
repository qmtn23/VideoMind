<script setup lang="ts">
import { NDrawer, NDrawerContent, NScrollbar, NSpin, NEmpty, NTag } from 'naive-ui';

defineOptions({ name: 'ChunkListDrawer' });

const props = defineProps<{
  fileMd5: string;
  fileName: string;
}>();

const visible = defineModel<boolean>('visible', { default: false });

interface Chunk {
  chunkId: number;
  textContent: string;
}

const loading = ref(false);
const chunks = ref<Chunk[]>([]);

async function loadChunks() {
  if (!props.fileMd5) return;
  loading.value = true;
  chunks.value = [];
  try {
    const { data, error } = await request<Chunk[]>({
      url: `/documents/${props.fileMd5}/chunks`
    });
    if (!error && data) {
      chunks.value = data;
    }
  } finally {
    loading.value = false;
  }
}

watch(visible, val => {
  if (val) loadChunks();
  else chunks.value = [];
});
</script>

<template>
  <NDrawer v-model:show="visible" :width="620" placement="right">
    <NDrawerContent :title="`分块列表 — ${fileName}`" closable>
      <NSpin :show="loading">
        <NEmpty v-if="!loading && chunks.length === 0" description="暂无分块数据" class="mt-40px" />
        <div v-else class="flex flex-col gap-12px">
          <div
            v-for="chunk in chunks"
            :key="chunk.chunkId"
            class="rounded-8px border border-solid border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800 p-12px"
          >
            <div class="mb-8px flex items-center gap-8px">
              <NTag type="info" size="small"># {{ chunk.chunkId }}</NTag>
            </div>
            <p class="m-0 text-3.5 leading-6 text-gray-700 dark:text-gray-300 whitespace-pre-wrap break-all">
              {{ chunk.textContent }}
            </p>
          </div>
        </div>
      </NSpin>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped></style>
