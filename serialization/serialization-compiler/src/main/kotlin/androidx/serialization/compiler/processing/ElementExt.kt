/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.serialization.compiler.processing

import com.google.auto.common.MoreElements
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import kotlin.reflect.KClass

/** Casts this element to a [TypeElement] using [MoreElements.asType]. */
internal fun Element.asTypeElement(): TypeElement {
    return MoreElements.asType(this)
}

/** Casts this element to a [VariableElement] using [MoreElements.asVariable]. */
internal fun Element.asVariableElement(): VariableElement {
    return MoreElements.asVariable(this)
}

/** Determines if this element is private by the presence of [Modifier.PRIVATE] in it modifiers. */
internal fun Element.isPrivate(): Boolean {
    return Modifier.PRIVATE in modifiers
}

/** Get an annotation mirror on this element, throwing if it is not directly present. */
internal operator fun Element.get(annotationClass: KClass<out Annotation>): AnnotationMirror {
    return requireNotNull(getAnnotationMirror(annotationClass)) {
        "Expected an annotation of type ${annotationClass.qualifiedName} to be directly present " +
                "on element $this"
    }
}

/**
 * Get an annotation mirror if directly present on this element or else `null`.
 */
internal fun Element.getAnnotationMirror(
    annotationClass: KClass<out Annotation>
): AnnotationMirror? {
    return MoreElements.getAnnotationMirror(this, annotationClass.java).orNull()
}