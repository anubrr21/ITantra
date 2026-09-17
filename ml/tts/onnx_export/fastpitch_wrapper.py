import math
import types

import torch
import torch.nn as nn
from TTS.tts.layers.generic.pos_encoding import PositionalEncoding


def _exportable_pos_encoding_forward(self, x, mask=None, first_idx=None, last_idx=None):
    x = x * math.sqrt(self.channels)
    if first_idx is None:
        if mask is not None:
            pos_enc = self.pe[:, :, : x.size(2)] * mask
        else:
            pos_enc = self.pe[:, :, : x.size(2)]
        if self.use_scale:
            x = x + self.scale * pos_enc
        else:
            x = x + pos_enc
    else:
        if self.use_scale:
            x = x + self.scale * self.pe[:, :, first_idx:last_idx]
        else:
            x = x + self.pe[:, :, first_idx:last_idx]
    if hasattr(self, "dropout"):
        x = self.dropout(x)
    return x


def make_model_onnx_exportable(model):
    for module in model.modules():
        if isinstance(module, PositionalEncoding):
            module.forward = types.MethodType(_exportable_pos_encoding_forward, module)
    return model


class FastPitchInferenceWrapper(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = make_model_onnx_exportable(model)

    def forward(self, token_ids: torch.Tensor, speaker_id: torch.Tensor) -> torch.Tensor:
        aux_input = {"d_vectors": None, "speaker_ids": speaker_id}
        outputs = self.model.inference(token_ids, aux_input)
        return outputs["model_outputs"]
