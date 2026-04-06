import { useLettaClient } from "@/providers/LettaProvider"
import { BlockUpdateParams } from "@letta-ai/letta-client/resources/blocks/blocks"
import { useMutation, UseMutationOptions } from "@tanstack/react-query"

export function useUpdateBlock(
  mutationOptions: UseMutationOptions<BlockUpdateParams, Error, any> = {},
) {
  const { lettaClient } = useLettaClient()

  return useMutation({
    mutationFn: ({ id, block }: { id: string; block: BlockUpdateParams }) =>
      lettaClient.blocks.update(id, block),
    ...mutationOptions,
  })
}
