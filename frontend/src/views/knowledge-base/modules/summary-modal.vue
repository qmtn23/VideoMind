<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NAlert, NButton, NEmpty, NModal, NScrollbar, NSpin } from 'naive-ui';
import { VueMarkdownIt, VueMarkdownItProvider } from 'vue-markdown-shiki';
import { fetchVideoSummary, generateVideoSummary, type VideoSummary } from '@/service/api/document';

defineOptions({ name: 'SummaryModal' });

const props = defineProps<{
  fileMd5: string;
  fileName: string;
}>();

const visible = defineModel<boolean>('visible', { default: false });

const loading = ref(false);
const generating = ref(false);
const summary = ref<VideoSummary | null>(null);
const errorMsg = ref('');

const hasSummary = computed(() => !!summary.value && !!summary.value.summary);

async function loadExisting() {
  if (!props.fileMd5) return;
  loading.value = true;
  errorMsg.value = '';
  summary.value = null;
  try {
    const { data, error, response } = await fetchVideoSummary(props.fileMd5);
    if (error) {
      const status = response?.status;
      if (status === 404) {
        return;
      }
      errorMsg.value = (error as any)?.message || '加载摘要失败';
      return;
    }
    if (data) summary.value = data;
  } finally {
    loading.value = false;
  }
}

async function handleGenerate() {
  if (!props.fileMd5 || generating.value) return;
  generating.value = true;
  errorMsg.value = '';
  try {
    const { data, error, response } = await generateVideoSummary(props.fileMd5);
    if (error) {
      const status = response?.status;
      const backendMsg = (response?.data as any)?.message;
      errorMsg.value = backendMsg || (error as any)?.message || `生成失败（HTTP ${status ?? '未知'}）`;
      return;
    }
    if (data) {
      summary.value = data;
      window.$message?.success('摘要生成成功');
    }
  } finally {
    generating.value = false;
  }
}

watch(visible, val => {
  if (val) {
    loadExisting();
  } else {
    summary.value = null;
    errorMsg.value = '';
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    :title="`AI 摘要 — ${fileName}`"
    style="width: 80%; max-width: 820px"
    :bordered="false"
    size="small"
    :mask-closable="!generating"
    :close-on-esc="!generating"
  >
    <NSpin :show="loading" description="正在加载摘要…">
      <div class="min-h-280px">
        <NAlert v-if="errorMsg" type="error" :show-icon="true" class="mb-12px">
          {{ errorMsg }}
        </NAlert>

        <NAlert
          v-if="summary?.truncated"
          type="warning"
          :show-icon="true"
          class="mb-12px"
        >
          视频转写文本超长已自动截断，摘要仅基于前面部分生成。
        </NAlert>

        <template v-if="!loading && !hasSummary">
          <NEmpty description="该视频尚未生成摘要" class="my-32px">
            <template #extra>
              <NButton
                type="primary"
                size="medium"
                :loading="generating"
                :disabled="generating"
                @click="handleGenerate"
              >
                {{ generating ? 'AI 正在阅读视频…' : '点击生成摘要' }}
              </NButton>
            </template>
          </NEmpty>
        </template>

        <template v-else-if="hasSummary">
          <div class="mb-12px flex items-center justify-between">
            <span class="text-3.5 text-gray-500 dark:text-gray-400">
              生成时间：{{ summary?.generatedAt || '—' }}
            </span>
            <NButton
              size="small"
              type="primary"
              ghost
              :loading="generating"
              :disabled="generating"
              @click="handleGenerate"
            >
              {{ generating ? '重新生成中…' : '重新生成' }}
            </NButton>
          </div>
          <NScrollbar style="max-height: 60vh">
            <div class="summary-content rounded-8px bg-gray-50 dark:bg-gray-800 p-16px">
              <VueMarkdownItProvider>
                <VueMarkdownIt :content="summary!.summary" />
              </VueMarkdownItProvider>
            </div>
          </NScrollbar>
        </template>
      </div>
    </NSpin>
  </NModal>
</template>

<style scoped lang="scss">
.summary-content {
  :deep(h2) {
    margin-top: 16px;
    margin-bottom: 8px;
    font-size: 16px;
    font-weight: 600;
  }
  :deep(h2:first-child) {
    margin-top: 0;
  }
  :deep(ul) {
    padding-left: 20px;
  }
  :deep(p) {
    line-height: 1.7;
    margin: 4px 0;
  }
}
</style>
