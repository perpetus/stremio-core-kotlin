use stremio_core::deep_links::LibraryItemDeepLinks;
use stremio_core::models::ctx::Ctx;
use stremio_core::types::library::LibraryItem;
use stremio_core::types::streams::StreamsItemKey;

use crate::bridge::ToProtobuf;
use crate::protobuf::stremio::core::types;

impl ToProtobuf<types::LibraryItem, (&Ctx, Option<usize>)> for LibraryItem {
    fn to_protobuf<E: stremio_core::runtime::Env + 'static>(
        &self,
        (ctx, maybe_notifications): &(&Ctx, Option<usize>),
    ) -> types::LibraryItem {
        let notifications = ctx.notifications
            .items
            .get(&self.id)
            .map(|notifs| {
                notifs.values()
                    .filter(|notif| {
                        match &self.state.last_watched {
                            Some(last_watched) => notif.video_released > *last_watched,
                            None => true,
                        }
                    })
                    .count()
            })
            .unwrap_or_default();
        let streams_item = self.state.video_id.as_ref().and_then(|video_id| {
            ctx.streams.items.get(&StreamsItemKey {
                meta_id: self.id.to_owned(),
                video_id: video_id.to_owned(),
            })
        });
        let settings = &ctx.profile.settings;
        let streaming_server_url = &settings.streaming_server_url;
        let deep_links =
            LibraryItemDeepLinks::from((self, streams_item, Some(streaming_server_url), settings));
        let is_completed = if self.r#type == "series" {
            if let Some(watched_field) = &self.state.watched {
                let watched_str = watched_field.to_string();
                let mut components: Vec<&str> = watched_str.split(':').collect();
                if components.len() >= 3 {
                    if let (Some(bitfield_str), Some(len_str)) = (components.pop(), components.pop()) {
                        if let Ok(total) = len_str.parse::<usize>() {
                            use std::convert::TryFrom;
                            if let Ok(bitfield) = stremio_watched_bitfield::BitField8::try_from((bitfield_str.to_string(), Some(total))) {
                                total > 0 && (0..total).all(|i| bitfield.get(i))
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }
            } else {
                false
            }
        } else {
            self.state.times_watched > 0
        };
        let remaining_episodes = if self.r#type == "series" {
            Some(notifications as i32)
        } else {
            None
        };
        types::LibraryItem {
            id: self.id.to_string(),
            r#type: self.r#type.to_string(),
            name: self.name.to_string(),
            poster: self.poster.to_protobuf::<E>(&()),
            poster_shape: self.poster_shape.to_protobuf::<E>(&()) as i32,
            state: types::LibraryItemState {
                time_offset: self.state.time_offset,
                duration: self.state.duration,
                video_id: self.state.video_id.clone(),
                no_notif: self.state.no_notif,
            },
            behavior_hints: self.behavior_hints.to_protobuf::<E>(&()),
            deep_links: types::MetaItemDeepLinks {
                meta_details_videos: deep_links.meta_details_videos,
                meta_details_streams: deep_links.meta_details_streams,
                player: deep_links.player,
            },
            progress: self.progress(),
            watched: is_completed,
            notifications: notifications as u64,
            remaining_episodes,
        }
    }
}
