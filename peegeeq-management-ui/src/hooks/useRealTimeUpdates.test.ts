import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useConnectionStatus } from './useRealTimeUpdates'

describe('useConnectionStatus', () => {
    it('starts with an empty connections map and all flags false/zero', () => {
        const { result } = renderHook(() => useConnectionStatus())
        expect(result.current.connections).toEqual({})
        expect(result.current.isAnyConnected).toBe(false)
        expect(result.current.connectionCount).toBe(0)
    })

    it('updateConnection adds the named entry to the map', () => {
        const { result } = renderHook(() => useConnectionStatus())
        act(() => {
            result.current.updateConnection('ws', true)
        })
        expect(result.current.connections).toEqual({ ws: true })
        expect(result.current.connectionCount).toBe(1)
    })

    it('isAnyConnected is true when at least one entry is true', () => {
        const { result } = renderHook(() => useConnectionStatus())
        act(() => {
            result.current.updateConnection('ws', false)
            result.current.updateConnection('sse', true)
        })
        expect(result.current.isAnyConnected).toBe(true)
        expect(result.current.allConnected).toBe(false)
    })

    it('allConnected is true only when every entry is true', () => {
        const { result } = renderHook(() => useConnectionStatus())
        act(() => {
            result.current.updateConnection('ws', true)
            result.current.updateConnection('sse', true)
        })
        expect(result.current.allConnected).toBe(true)
        expect(result.current.connectionCount).toBe(2)
    })

    it('updateConnection can flip an entry from true to false', () => {
        const { result } = renderHook(() => useConnectionStatus())
        act(() => { result.current.updateConnection('ws', true) })
        act(() => { result.current.updateConnection('ws', false) })
        expect(result.current.connections.ws).toBe(false)
        expect(result.current.isAnyConnected).toBe(false)
        expect(result.current.connectionCount).toBe(0)
    })

    it('connectionCount counts only true entries', () => {
        const { result } = renderHook(() => useConnectionStatus())
        act(() => {
            result.current.updateConnection('ws', true)
            result.current.updateConnection('sse', false)
            result.current.updateConnection('rest', true)
        })
        expect(result.current.connectionCount).toBe(2)
    })
})
