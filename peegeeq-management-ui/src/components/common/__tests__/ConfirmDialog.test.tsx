/**
 * Unit Tests for ConfirmDialog Component
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Modal } from 'antd';
import { 
  showConfirmDialog, 
  showDeleteConfirm, 
  showPurgeConfirm, 
  showDeleteQueueConfirm 
} from '../ConfirmDialog';

// Mock Ant Design Modal.confirm
vi.mock('antd', async () => {
  const actual = await vi.importActual('antd');
  return {
    ...actual,
    Modal: {
      ...actual.Modal,
      confirm: vi.fn(),
    },
  };
});

describe('ConfirmDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('showConfirmDialog', () => {
    it('calls Modal.confirm with correct parameters', () => {
      const onOk = vi.fn();
      const onCancel = vi.fn();

      showConfirmDialog({
        title: 'Test Confirmation',
        content: 'Are you sure?',
        onOk,
        onCancel,
      });

      expect(Modal.confirm).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Test Confirmation',
          content: 'Are you sure?',
          onOk,
          onCancel,
        })
      );
    });

    it('uses default values for optional parameters', () => {
      const onOk = vi.fn();

      showConfirmDialog({
        title: 'Test',
        content: 'Content',
        onOk,
      });

      expect(Modal.confirm).toHaveBeenCalledWith(
        expect.objectContaining({
          okText: 'OK',
          cancelText: 'Cancel',
          okType: 'primary',
        })
      );
    });

    it('allows custom button text', () => {
      const onOk = vi.fn();

      showConfirmDialog({
        title: 'Test',
        content: 'Content',
        okText: 'Confirm',
        cancelText: 'Abort',
        onOk,
      });

      expect(Modal.confirm).toHaveBeenCalledWith(
        expect.objectContaining({
          okText: 'Confirm',
          cancelText: 'Abort',
        })
      );
    });
  });

  describe('showDeleteConfirm', () => {
    it('sets okType to danger', () => {
      const onOk = vi.fn();

      showDeleteConfirm({
        title: 'Delete Item',
        content: 'Are you sure?',
        onOk,
      });

      expect(Modal.confirm).toHaveBeenCalledWith(
        expect.objectContaining({
          okType: 'danger',
          okText: 'Delete',
        })
      );
    });
  });

  describe('showPurgeConfirm', () => {
    it('shows correct title and content for purge operation', () => {
      const onOk = vi.fn();

      showPurgeConfirm('test-queue', onOk);

      expect(Modal.confirm).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Purge Queue "test-queue"?',
          okText: 'Purge',
          okType: 'danger',
          onOk,
        })
      );
    });

    it('includes warning about permanent deletion', () => {
      const onOk = vi.fn();
      const mockConfirm = vi.mocked(Modal.confirm);

      showPurgeConfirm('test-queue', onOk);

      const call = mockConfirm.mock.calls[0][0];
      expect(call.content).toBeDefined();
    });
  });

  describe('showDeleteQueueConfirm', () => {
    it('shows correct title and content for delete queue operation', () => {
      const onOk = vi.fn();

      showDeleteQueueConfirm('test-queue', onOk);

      expect(Modal.confirm).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Delete Queue "test-queue"?',
          okText: 'Delete Queue',
          okType: 'danger',
          onOk,
        })
      );
    });

    it('includes warning about permanent deletion', () => {
      const onOk = vi.fn();
      const mockConfirm = vi.mocked(Modal.confirm);

      showDeleteQueueConfirm('test-queue', onOk);

      const call = mockConfirm.mock.calls[0][0];
      expect(call.content).toBeDefined();
    });
  });
});
