/*
 * Copyright 1998-2018 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.topic;

import com.google.common.collect.ImmutableList;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.org.linux.gallery.ImageService;
import ru.org.linux.gallery.UploadedImagePreview;
import ru.org.linux.group.Group;
import ru.org.linux.markup.MessageTextService;
import ru.org.linux.poll.PollDao;
import ru.org.linux.poll.PollNotFoundException;
import ru.org.linux.poll.PollVariant;
import ru.org.linux.section.Section;
import ru.org.linux.section.SectionService;
import ru.org.linux.site.ScriptErrorException;
import ru.org.linux.spring.dao.DeleteInfoDao;
import ru.org.linux.spring.dao.MessageText;
import ru.org.linux.tag.TagName;
import ru.org.linux.user.*;
import ru.org.linux.util.LorHttpUtils;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.*;

@Service
public class TopicService {
  private static final Logger logger = LoggerFactory.getLogger(TopicService.class);

  @Autowired
  private TopicDao topicDao;

  @Autowired
  private SectionService sectionService;

  @Autowired
  private ImageService imageService;

  @Autowired
  private PollDao pollDao;

  @Autowired
  private UserEventService userEventService;

  @Autowired
  private TopicTagService topicTagService;

  @Autowired
  private UserService userService;

  @Autowired
  private UserTagService userTagService;

  @Autowired
  private UserDao userDao;

  @Autowired
  private DeleteInfoDao deleteInfoDao;

  @Autowired
  private MessageTextService textService;

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public int addMessage(
          HttpServletRequest request,
          AddTopicRequest form,
          MessageText message,
          Group group,
          User user,
          UploadedImagePreview imagePreview,
          Topic previewMsg
  ) throws ScriptErrorException {
    final int msgid = topicDao.saveNewMessage(
            previewMsg,
            user,
            message,
            request.getHeader("User-Agent"),
            group
    );

    Section section = sectionService.getSection(group.getSectionId());

    if (section.isImagepost() && imagePreview == null) {
      throw new ScriptErrorException("scrn==null!?");
    }

    if (imagePreview!=null) {
      imageService.saveScreenshot(imagePreview, msgid);
    }

    if (section.isPollPostAllowed()) {
      pollDao.createPoll(Arrays.asList(form.getPoll()), form.isMultiSelect(), msgid);
    }

    List<String> tags = TagName.parseAndSanitizeTags(form.getTags());

    topicTagService.updateTags(msgid, ImmutableList.of(), tags);

    if (!previewMsg.isDraft()) {
      if (section.isPremoderated()) {
        sendEvents(message, msgid, ImmutableList.of(), user.getId());
      } else {
        sendEvents(message, msgid, tags, user.getId());
      }
    }

    String logmessage = "Написана тема " + msgid + ' ' + LorHttpUtils.getRequestIP(request);
    logger.info(logmessage);

    return msgid;
  }

  /**
   * Отправляет уведомления типа REF (ссылка на пользователя) и TAG (уведомление по тегу)
   *
   * @param message текст сообщения
   * @param msgid идентификатор сообщения
   * @param author автор сообщения (ему не будет отправлено уведомление)
   */
  private void sendEvents(MessageText message, int msgid, List<String> tags, int author) {
    Set<Integer> notifiedUsers = userEventService.getNotifiedUsers(msgid);

    Set<User> userRefs = textService.mentions(message);
    userRefs = userRefs.stream()
            .filter(p -> !userService.isIgnoring(p.getId(), author))
            .collect(Collectors.toSet());

    // оповещение пользователей по тегам
    List<Integer> userIdListByTags = userTagService.getUserIdListByTags(author, tags);

    Set<Integer> userRefIds = userRefs
            .stream()
            .filter(userRef -> !notifiedUsers.contains(userRef.getId()))
            .map(User::getId)
            .collect(Collectors.toSet());

    // не оповещать пользователей. которые ранее были оповещены через упоминание
    Iterable<Integer> tagUsers = userIdListByTags.stream()
            .filter(not(or(in(userRefIds), in(notifiedUsers)))).collect(Collectors.toList());

    userEventService.addUserRefEvent(userRefIds, msgid);
    userEventService.addUserTagEvent(tagUsers, msgid);
  }

  /**
   * Удаление топика и если удаляет модератор изменить автору score
   * @param message удаляемый топик
   * @param user удаляющий пользователь
   * @param reason причина удаления
   * @param bonus дельта изменения score автора топика
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public void deleteWithBonus(Topic message, User user, String reason, int bonus) {
    if (bonus>20 || bonus<0) {
      throw new IllegalArgumentException("Некорректное значение bonus");
    }

    if (user.isModerator() && bonus!=0 && user.getId()!=message.getUid() && !message.isDraft()) {
      boolean deleted = deleteTopic(message.getId(), user, reason, -bonus);

      if (deleted) {
        userDao.changeScore(message.getUid(), -bonus);
      }
    } else {
      deleteTopic(message.getId(), user, reason, 0);
    }
  }

  private boolean deleteTopic(int mid, User moderator, String reason, int bonus) {
    boolean deleted = topicDao.delete(mid);

    if (deleted) {
      deleteInfoDao.insert(mid, moderator, reason, bonus);
      userEventService.processTopicDeleted(ImmutableList.of(mid));
    }

    return deleted;
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public List<Integer> deleteByIPAddress(String ip, Timestamp startTime, User moderator, String reason) {
    List<Integer> topicIds = topicDao.getAllByIPForUpdate(ip, startTime);

    return massDelete(moderator, topicIds, reason);
  }

  /**
   * Массовое удаление всех топиков пользователя.
   *
   * @param user      пользователь для экзекуции
   * @param moderator экзекутор-модератор
   * @return список удаленных топиков
   * @throws UserNotFoundException генерирует исключение если пользователь отсутствует
   */
  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public List<Integer> deleteAllByUser(User user, User moderator) {
    List<Integer> topics = topicDao.getUserTopicForUpdate(user);

    return massDelete(moderator, topics, "Блокировка пользователя с удалением сообщений");
  }

  private List<Integer> massDelete(User moderator, Iterable<Integer> topics, String reason) {
    List<Integer> deletedTopics = new ArrayList<>();

    for (int mid : topics) {
      boolean deleted = topicDao.delete(mid);

      if (deleted) {
        deleteInfoDao.insert(mid, moderator, reason, 0);
        deletedTopics.add(mid);
      }
    }

    userEventService.processTopicDeleted(deletedTopics);

    return deletedTopics;
  }

  private static boolean sendTagEventsNeeded(Section section, Topic oldMsg, boolean commit) {
    boolean needCommit = section.isPremoderated() && !oldMsg.isCommited();
    boolean fresh = oldMsg.getEffectiveDate().isAfter(DateTime.now().minusMonths(1));

    return commit || (!needCommit && fresh);
  }

  @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
  public boolean updateAndCommit(
          Topic newMsg,
          Topic oldMsg,
          User user,
          @Nullable List<String> newTags,
          MessageText newText,
          boolean commit,
          Integer changeGroupId,
          int bonus,
          List<PollVariant> pollVariants,
          boolean multiselect,
          Map<Integer, Integer> editorBonus,
          UploadedImagePreview imagePreview
  ) throws IOException {
    boolean modified = topicDao.updateMessage(oldMsg, newMsg, user, newTags, newText.text(), imagePreview);

    if (!newMsg.isDraft() && !newMsg.isExpired()) {
      Section section = sectionService.getSection(oldMsg.getSectionId());

      if (newTags!=null && sendTagEventsNeeded(section, oldMsg, commit)) {
        sendEvents(newText, oldMsg.getId(), newTags, oldMsg.getUid());
      } else {
        sendEvents(newText, oldMsg.getId(), ImmutableList.of(), oldMsg.getUid());
      }
    }

    try {
      if (pollVariants!=null && pollDao.updatePoll(oldMsg, pollVariants, multiselect)) {
        modified = true;
      }
    } catch (PollNotFoundException e) {
      throw new RuntimeException(e);
    }

    if (oldMsg.isDraft() && !newMsg.isDraft()) {
      topicDao.publish(newMsg);
    }

    if (commit) {
      if (changeGroupId != null) {
        if (oldMsg.getGroupId() != changeGroupId) {
          topicDao.changeGroup(oldMsg, changeGroupId);
        }
      }

      commit(oldMsg, user, bonus, editorBonus);
    }

    if (modified) {
      logger.info("сообщение " + oldMsg.getId() + " исправлено " + user.getNick());
    }

    return modified;
  }

  private void commit(Topic msg, User commiter, int bonus, Map<Integer, Integer> editorBonus) {
    if (bonus < 0 || bonus > 20) {
      throw new IllegalStateException("Неверное значение bonus");
    }

    if (msg.isDraft()) {
      topicDao.publish(msg);
    }

    topicDao.commit(msg, commiter);

    userDao.changeScore(msg.getUid(), bonus);

    if (editorBonus!=null) {
      for (Map.Entry<Integer, Integer> entry : editorBonus.entrySet()) {
        userDao.changeScore(entry.getKey(), entry.getValue());
      }
    }
  }
}
