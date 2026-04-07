import { renderHook, waitFor, act } from "@testing-library/react-native"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  useAgentMessages,
  useResetChatMessages,
  getAgentMessagesQueryKey,
} from "../use-agent-messages"
import { useLettaClient } from "@/providers/LettaProvider"
import { createElement, type ReactNode } from "react"

// Mock the LettaProvider
jest.mock("@/providers/LettaProvider", () => ({
  useLettaClient: jest.fn(),
}))

// Mock the utils
jest.mock("../utils", () => ({
  filterMessages: jest.fn((messages) => messages),
}))

const mockUseLettaClient = useLettaClient as jest.MockedFunction<typeof useLettaClient>

describe("use-agent-messages hooks", () => {
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

  describe("getAgentMessagesQueryKey", () => {
    it("should return query key with agentId and default conversation", () => {
      const key = getAgentMessagesQueryKey("agent-1")
      expect(key).toEqual(["agentMessages", "agent-1", "default"])
    })

    it("should return query key with agentId and conversationId", () => {
      const key = getAgentMessagesQueryKey("agent-1", "conv-123")
      expect(key).toEqual(["agentMessages", "agent-1", "conv-123"])
    })

    it("should use default when conversationId is null", () => {
      const key = getAgentMessagesQueryKey("agent-1", null)
      expect(key).toEqual(["agentMessages", "agent-1", "default"])
    })
  })

  describe("useAgentMessages", () => {
    it("should fetch messages for default conversation when no conversationId", async () => {
      const mockMessages = [
        { id: "msg-1", text: "Hello" },
        { id: "msg-2", text: "World" },
      ]

      const mockList = jest.fn().mockResolvedValue({
        getPaginatedItems: () => mockMessages,
      })

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          agents: {
            messages: {
              list: mockList,
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useAgentMessages("agent-1"), {
        wrapper: createWrapper(),
      })

      await waitFor(() => {
        expect(mockList).toHaveBeenCalled()
      })

      expect(mockList).toHaveBeenCalledWith("agent-1")
    })

    it("should fetch messages for specific conversation when conversationId provided", async () => {
      const mockMessages = [{ id: "msg-1", text: "Conversation message" }]

      const mockConversationList = jest.fn().mockResolvedValue({
        getPaginatedItems: () => mockMessages,
      })

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          conversations: {
            messages: {
              list: mockConversationList,
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useAgentMessages("agent-1", "conv-123"), {
        wrapper: createWrapper(),
      })

      await waitFor(() => {
        expect(mockConversationList).toHaveBeenCalled()
      })

      expect(mockConversationList).toHaveBeenCalledWith("conv-123")
    })

    it("should not fetch when agentId is not provided", () => {
      const mockList = jest.fn()

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          agents: {
            messages: {
              list: mockList,
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useAgentMessages(""), {
        wrapper: createWrapper(),
      })

      expect(result.current.isFetching).toBe(false)
      expect(mockList).not.toHaveBeenCalled()
    })

    it("should not fetch when lettaClient is not available", () => {
      mockUseLettaClient.mockReturnValue({
        lettaClient: null,
      } as any)

      const { result } = renderHook(() => useAgentMessages("agent-1"), {
        wrapper: createWrapper(),
      })

      expect(result.current.isFetching).toBe(false)
    })

    it("should return empty array as initial data", () => {
      mockUseLettaClient.mockReturnValue({
        lettaClient: null,
      } as any)

      const { result } = renderHook(() => useAgentMessages("agent-1"), {
        wrapper: createWrapper(),
      })

      expect(result.current.data).toEqual([])
    })
  })

  describe("useResetChatMessages", () => {
    it("should reset messages for default conversation", async () => {
      const mockReset = jest.fn().mockResolvedValue({})

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          agents: {
            messages: {
              reset: mockReset,
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useResetChatMessages(), {
        wrapper: createWrapper(),
      })

      await act(async () => {
        await result.current.mutateAsync({
          agentId: "agent-1",
          add_default_initial_messages: true,
        })
      })

      expect(mockReset).toHaveBeenCalledWith("agent-1", {
        add_default_initial_messages: true,
      })
    })

    it("should invalidate queries on success", async () => {
      const mockReset = jest.fn().mockResolvedValue({})

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          agents: {
            messages: {
              reset: mockReset,
            },
          },
        },
      } as any)

      const invalidateSpy = jest.spyOn(queryClient, "invalidateQueries")

      const { result } = renderHook(() => useResetChatMessages(), {
        wrapper: createWrapper(),
      })

      await act(async () => {
        await result.current.mutateAsync({
          agentId: "agent-1",
        })
      })

      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ["agentMessages", "agent-1", "default"],
      })
    })

    it("should handle reset errors", async () => {
      const mockError = new Error("Reset failed")
      const mockReset = jest.fn().mockRejectedValue(mockError)

      mockUseLettaClient.mockReturnValue({
        lettaClient: {
          agents: {
            messages: {
              reset: mockReset,
            },
          },
        },
      } as any)

      const { result } = renderHook(() => useResetChatMessages(), {
        wrapper: createWrapper(),
      })

      await expect(
        act(async () => {
          await result.current.mutateAsync({
            agentId: "agent-1",
          })
        }),
      ).rejects.toThrow("Reset failed")
    })
  })
})
