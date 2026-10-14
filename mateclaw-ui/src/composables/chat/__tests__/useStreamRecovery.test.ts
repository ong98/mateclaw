import { afterEach, describe, expect, it, vi } from 'vitest'
import { useStream } from '../useStream'

describe('流连接恢复', () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('恢复耗尽后停止重试，原业务指令只发送一次', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(''))
      .mockImplementation(() => Promise.resolve(new Response('', { status: 503 })))
    vi.stubGlobal('fetch', fetchMock)
    const stream = useStream({ url: '/api/v1/chat/stream' })
    const running = stream.connect({ conversationId: 'conv', message: '生成报告' })
    await vi.runAllTimersAsync(); await running
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1)
    expect(stream.error.value).not.toBeNull()
  })

  it('恢复退避期间切换会话，旧恢复不能拉回旧会话或污染新结果', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(''))
      .mockResolvedValueOnce(new Response('id: 1\nevent: content_delta\ndata: {"delta":"新结果"}\n\nid: 2\nevent: done\ndata: {}\n\n'))
    vi.stubGlobal('fetch', fetchMock)
    const stream = useStream({ url: '/api/v1/chat/stream' })
    const deltas: string[] = []
    stream.on('content_delta', data => deltas.push(data.delta))
    const old = stream.connect({ conversationId: 'old', message: '旧任务' })
    await vi.advanceTimersByTimeAsync(0)
    const current = stream.connect({ conversationId: 'new', message: '新任务' })
    await vi.runAllTimersAsync(); await Promise.all([old, current])
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(deltas).toEqual(['新结果'])
    expect(stream.error.value).toBeNull()
  })

  it('已收到结束事件时正常 EOF 不触发额外状态查询', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetchMock = vi.fn().mockResolvedValue(new Response('id: 1\nevent: done\ndata: {}\n\n'))
    vi.stubGlobal('fetch', fetchMock)
    const stream = useStream({ url: '/api/v1/chat/stream' })
    const running = stream.connect({ conversationId: 'conv', message: '生成报告' })
    await vi.runAllTimersAsync(); await running
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(stream.error.value).toBeNull()
  })

  it('EOF 后只重订阅，保留事件去重并且不重新发送原指令', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('id: 1\nevent: content_delta\ndata: {"delta":"a"}\n\n'))
      .mockResolvedValueOnce(new Response('{"data":{"streamStatus":"running"}}'))
      .mockResolvedValueOnce(new Response('id: 1\nevent: content_delta\ndata: {"delta":"a"}\n\nid: 2\nevent: done\ndata: {}\n\n'))
    vi.stubGlobal('fetch', fetchMock)
    const stream = useStream({ url: '/api/v1/chat/stream' })
    const deltas: string[] = []
    stream.on('content_delta', data => deltas.push(data.delta))
    const running = stream.connect({ conversationId: 'conv', message: '创建报告' })
    await vi.runAllTimersAsync()
    await running
    expect(deltas).toEqual(['a'])
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/conversations/conv/status')
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ conversationId: 'conv', reconnect: true, lastEventId: 1 })
  })

  it('后台已完成时触发持久化消息核对，主动断开时不恢复', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(''))
      .mockResolvedValueOnce(new Response('{"data":{"streamStatus":"idle"}}'))
    vi.stubGlobal('fetch', fetchMock)
    const stream = useStream({ url: '/api/v1/chat/stream' })
    const done = vi.fn()
    stream.on('done', done)
    const running = stream.connect({ conversationId: 'conv' })
    await vi.runAllTimersAsync(); await running
    expect(done).toHaveBeenCalledWith(expect.objectContaining({ persisted: true, conversationId: 'conv' }))

    fetchMock.mockReset().mockResolvedValue(new Response(''))
    const stopped = stream.connect({ conversationId: 'other' })
    await Promise.resolve()
    stream.disconnect()
    await vi.runAllTimersAsync(); await stopped
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
