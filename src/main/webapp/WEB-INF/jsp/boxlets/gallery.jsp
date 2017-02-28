<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="lor" uri="http://www.linux.org.ru" %>
<%@ taglib prefix="l" uri="http://www.linux.org.ru" %>
<%--
  ~ Copyright 1998-2015 Linux.org.ru
  ~    Licensed under the Apache License, Version 2.0 (the "License");
  ~    you may not use this file except in compliance with the License.
  ~    You may obtain a copy of the License at
  ~
  ~        http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~    Unless required by applicable law or agreed to in writing, software
  ~    distributed under the License is distributed on an "AS IS" BASIS,
  ~    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~    See the License for the specific language governing permissions and
  ~    limitations under the License.
  --%>
<%--@elvariable id="items" type="java.util.List<ru.org.linux.gallery.PreparedGalleryItem>"--%>

  <h2><a href="/gallery/">Галерея</a></h2>

  <div class="boxlet_content boxlet-gallery">
    <c:forEach var="item" items="${items}">
      <div style="margin-bottom: 1em">
      <div align="center">
        <c:url var="url" value="${item.item.link}"/>
        <a href="${url}">
          <img    sizes="(min-width: 60em) 24vw, 100vw"
                  srcset="${item.item.image.srcset}"
                  src="${item.item.image.medium}"
                  alt="Скриншот: <l:title>${item.item.title}</l:title>">
        </a>
      </div>
      <a href="${url}">${item.item.title}</a> от ${item.user.nick} (${item.item.stat})
      </div>
    </c:forEach>
    <a href="/gallery/">другие скриншоты...</a>
  </div>