package org.thoughtcrime.securesms.conversation.ui.edit

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.v2.data.AttachmentHelper
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.recipients.Recipient

object EditMessageHistoryRepository {

  fun getEditHistory(messageId: Long): Observable<List<ConversationMessage>> {
    return Observable.create { emitter ->
      val threadId: Long = SignalDatabase.messages.getThreadIdForMessage(messageId)
      if (threadId < 0) {
        emitter.onNext(emptyList())
        return@create
      }

      val databaseObserver: DatabaseObserver = ApplicationDependencies.getDatabaseObserver()
      val observer = DatabaseObserver.Observer { emitter.onNext(getEditHistorySync(messageId)) }

      databaseObserver.registerConversationObserver(threadId, observer)

      emitter.setCancellable { databaseObserver.unregisterObserver(observer) }
      emitter.onNext(getEditHistorySync(messageId))
    }.subscribeOn(Schedulers.io())
  }

  private fun getEditHistorySync(messageId: Long): List<ConversationMessage> {
    val context = ApplicationDependencies.getApplication()
    val records = SignalDatabase
      .messages
      .getMessageEditHistory(messageId)
      .toList()
      .reversed()

    if (records.isEmpty()) {
      return emptyList()
    }

    val attachmentHelper = AttachmentHelper()
      .apply {
        addAll(records)
        fetchAttachments()
      }

    val threadRecipient: Recipient = requireNotNull(SignalDatabase.threads.getRecipientForThreadId(records[0].threadId))

    return attachmentHelper
      .buildUpdatedModels(context, records)
      .map { ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(context, it, threadRecipient) }
  }
}
