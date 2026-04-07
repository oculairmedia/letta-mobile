import { renderHook, waitFor } from "@testing-library/react-native"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  useConversations,
  useCreateConversation,
  useDeleteConversation,
  useUpdateConversation,
  useForkConversation,
} from "../use-conversations"
import { useLettaClient } from "@/providers/LettaProvider"
import { createElement, type ReactNode } from "react"

// Mock the LettaProvider
jest.mock("@/providers/LettaProvider", () => ({
  useLettaClient: jest.fn(),
}))

const mockUseLettaClient = useLettaClient as jest.MockedFunction<typeof useLettaClient>

describe("use-conversations hooks", () => {
  let queryClient: QueryClient

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
        mutations: {
          retry: false,
        },
      },
    })
    jest.clearAllMocks()
  })

  afterEach(() => {
    queryClient.clear()
  })

  describe("useConversations", () => {
    it("should fetch conversations for an agent", async () => {
      const mockConversations = [
        { id: "conv-1", agent_id: "agent-1", summary: "Test conversation" },
        { id: "conv-2", agent_id: "agent-1", summary: "Another conversation" },
      ]

      const mockList = jest.fn().mockResolvedValue(mockConversations)

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            list: mockList,
          },
        },
      } as any)

      const { result } = renderHook(() => useConversations("agent-1"), {
        wrapper: createWrapper(),
      })

      await waitFor(() => {
        expect(result.current.data).toBeDefined()
      })

      expect(mockList).toHaveBeenCalledWith({
        agent_id: "agent-1",
        order: "desc",
        order_by: "last_message_at",
      })
      expect(result.current.data).toEqual(mockConversations)
    })

    it("should not fetch when agentId is not provided", async () => {
      const mockList = jest.fn()

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            list: mockList,
          },
        },
      } as any)

      const { result } = renderHook(() => useConversations(""), {
        wrapper: createWrapper(),
      })

      expect(result.current.isFetching).toBe(false)
      expect(mockList).not.toHaveBeenCalled()
    })
  })

  describe("useCreateConversation", () => {
    it("should create a new conversation", async () => {
      const mockCreate = jest.fn().mockResolvedValue({
        id: "new-conv",
        agent_id: "agent-1",
        summary: null,
      })

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            create: mockCreate,
          },
        },
      } as any)

      const { result } = renderHook(() => useCreateConversation(), {
        wrapper: createWrapper(),
      })

      await result.current.mutateAsync({ agentId: "agent-1" })

      expect(mockCreate).toHaveBeenCalledWith({
        agent_id: "agent-1",
        summary: undefined,
      })
    })

    it("should create a conversation with summary", async () => {
      const mockCreate = jest.fn().mockResolvedValue({
        id: "new-conv",
        agent_id: "agent-1",
        summary: "Test summary",
      })

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            create: mockCreate,
          },
        },
      } as any)

      const { result } = renderHook(() => useCreateConversation(), {
        wrapper: createWrapper(),
      })

      await result.current.mutateAsync({ agentId: "agent-1", summary: "Test summary" })

      expect(mockCreate).toHaveBeenCalledWith({
        agent_id: "agent-1",
        summary: "Test summary",
      })
    })
  })

  describe("useDeleteConversation", () => {
    it("should delete a conversation", async () => {
      const mockDelete = jest.fn().mockResolvedValue({})

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            delete: mockDelete,
          },
        },
      } as any)

      const { result } = renderHook(() => useDeleteConversation(), {
        wrapper: createWrapper(),
      })

      await result.current.mutateAsync({
        conversationId: "conv-1",
        agentId: "agent-1",
      })

      expect(mockDelete).toHaveBeenCalledWith("conv-1")
    })
  })

  describe("useUpdateConversation", () => {
    it("should update a conversation summary", async () => {
      const mockUpdate = jest.fn().mockResolvedValue({})

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            update: mockUpdate,
          },
        },
      } as any)

      const { result } = renderHook(() => useUpdateConversation(), {
        wrapper: createWrapper(),
      })

      await result.current.mutateAsync({
        conversationId: "conv-1",
        agentId: "agent-1",
        summary: "Updated summary",
      })

      expect(mockUpdate).toHaveBeenCalledWith("conv-1", { summary: "Updated summary" })
    })
  })

  describe("useForkConversation", () => {
    it("should fork a conversation", async () => {
      const forkedConversation = {
        id: "forked-conv",
        agent_id: "agent-1",
        summary: "Forked conversation",
      }

      const mockFork = jest.fn().mockResolvedValue(forkedConversation)

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            fork: mockFork,
          },
        },
      } as any)

      const { result } = renderHook(() => useForkConversation(), {
        wrapper: createWrapper(),
      })

      const response = await result.current.mutateAsync({
        conversationId: "conv-1",
        agentId: "agent-1",
      })

      expect(mockFork).toHaveBeenCalledWith("conv-1")
      expect(response.forked).toEqual(forkedConversation)
    })
  })
})
