// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.ui.feedback

import com.intellij.openapi.project.Project
import software.aws.toolkits.resources.message

class FeatureDevFeedbackDialog(project: Project) : FeedbackDialog(project) {
    override val notificationTitle = message("aws.notification.title.amazonq.feature_dev")
    override val feedbackPrompt = message("feedback.comment.textbox.title.amazonq.feature_dev")
    override val productName = "Amazon Q FeatureDev"

    init {
        title = message("feedback.title.amazonq.feature_dev")
    }
}
